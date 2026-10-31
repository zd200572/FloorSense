package com.floorsense.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.floorsense.app.MainActivity
import com.floorsense.app.R
import com.floorsense.app.data.db.FloorSenseDatabase
import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.model.ParkingRecord
import com.floorsense.app.data.sensor.HardwareSensorSource
import com.floorsense.app.data.settings.UserPreferencesRepository
import com.floorsense.app.domain.engine.ParkingMemoryEngine
import com.floorsense.app.domain.engine.ParkingTrackPhase
import com.floorsense.app.domain.filter.VerticalMotionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 下车“黄金 10 分钟”前台采集服务（规格书 §5）。
 *
 * 生命周期自毁铁律：
 * - 仅由车载蓝牙 ACL_DISCONNECTED 或用户手动触发启动；
 * - ParkingMemoryEngine 流转到 SETTLED（用户已回到地面 1F）或运行满 10 分钟仍未检测到上楼时，
 *   立即保存预估结果到 Room 并 stopSelf()，严禁全天候后台常驻耗电。
 */
class ParkingTrackerService : LifecycleService() {

    private var sensorSource: HardwareSensorSource? = null
    private var parkingEngine: ParkingMemoryEngine? = null
    private var pendingParkedAnchor = false
    private val persisted = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()

        if (parkingEngine == null) {
            beginTracking()
        }
        // 重复 onStartCommand（如蓝牙二次断开）不重置已锁定的车位锚点
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildProgressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun beginTracking() {
        val engine = ParkingMemoryEngine()
        parkingEngine = engine
        pendingParkedAnchor = true

        val source = HardwareSensorSource(this)
        sensorSource = source
        val gate = VerticalMotionGate()
        val prefs = UserPreferencesRepository(this)

        lifecycleScope.launch(Dispatchers.Default) {
            // 服务启动即视为已停好车：第一帧到达后锁定车位气压锚点
            source.observeSensorFrames().collect { frame ->
                val profile = profileOf(prefs)
                val filtered = gate.process(frame)
                if (pendingParkedAnchor) {
                    pendingParkedAnchor = false
                    engine.updateProfile(profile)
                    engine.onVehicleParked(frame.timestampMs, filtered.smoothPressureHpa)
                }
                engine.onSensorFrame(filtered, hasGps = frame.hasGpsSignal)
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            engine.parkingState.collect { state ->
                when (state.phase) {
                    ParkingTrackPhase.SETTLED -> settleAndStop(
                        floorLabel = state.estimatedFloorLabel ?: "1F",
                        floorIndex = state.depthMeters.roundToFloorIndex(),
                        depthMeters = state.depthMeters,
                        parkedAtMs = state.parkedTimestampMs ?: System.currentTimeMillis(),
                        confirmed = state.isConfirmedByReturnAscent
                    )
                    else -> updateProgressNotification(state)
                }
            }
        }

        // 省电铁律：10 分钟仍未结算则保存预估并自毁
        lifecycleScope.launch(Dispatchers.Default) {
            delay(MAX_TRACKING_MS)
            val state = parkingEngine?.parkingState?.value
            if (state?.phase != ParkingTrackPhase.SETTLED) {
                val depth = state?.depthMeters ?: 0f
                settleAndStop(
                    floorLabel = state?.estimatedFloorLabel ?: "1F",
                    floorIndex = depth.roundToFloorIndex(),
                    depthMeters = depth,
                    parkedAtMs = state?.parkedTimestampMs ?: System.currentTimeMillis(),
                    confirmed = false,
                    timedOut = true
                )
            }
        }
    }

    private suspend fun profileOf(prefs: UserPreferencesRepository): BuildingProfile =
        prefs.buildingProfile.first()

    private fun Float.roundToFloorIndex(): Int = when {
        this >= -1.8f -> 0
        else -> {
            val depthBelowB1 = -this - BuildingProfile.OFFICE.basementFirstHeightM
            if (depthBelowB1 <= 1.2f) -1 else -1 - (depthBelowB1 / BuildingProfile.OFFICE.basementSubHeightM).toInt()
        }
    }

    private suspend fun settleAndStop(
        floorLabel: String,
        floorIndex: Int,
        depthMeters: Float,
        parkedAtMs: Long,
        confirmed: Boolean,
        timedOut: Boolean = false
    ) {
        if (!persisted.compareAndSet(false, true)) return

        val record = ParkingRecord(
            floorLabel = floorLabel,
            floorIndex = floorIndex,
            depthMeters = depthMeters,
            parkedAtMs = parkedAtMs,
            settledAtMs = System.currentTimeMillis(),
            confirmedByReturnAscent = confirmed
        )
        try {
            FloorSenseDatabase.get(this).parkingRecordDao().insert(record)
        } catch (runtime: RuntimeException) {
            Log.e(TAG, "保存停车记录失败", runtime)
        }

        postSettlementNotification(floorLabel, depthMeters, timedOut)
        stopSelf()
    }

    private fun buildProgressNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_parking_notification)
            .setContentTitle(getString(R.string.tracking_title))
            .setContentText(getString(R.string.tracking_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateProgressNotification(state: com.floorsense.app.domain.engine.ParkingMemoryState) {
        if (state.parkedTimestampMs == null) return
        val text = state.estimatedFloorLabel?.let { label ->
            getString(R.string.tracking_estimate_text, label, abs(state.depthMeters))
        } ?: getString(R.string.tracking_text)
        val notification = NotificationCompat.Builder(this, CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_parking_notification)
            .setContentTitle(getString(R.string.tracking_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
        notifySafely(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun postSettlementNotification(floorLabel: String, depthMeters: Float, timedOut: Boolean) {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (timedOut) {
            getString(R.string.settle_timeout_text, floorLabel)
        } else {
            getString(R.string.settle_text, floorLabel, abs(depthMeters))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_parking_notification)
            .setContentTitle(getString(R.string.settle_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notifySafely(NOTIFICATION_ID_RESULT, notification)
    }

    private fun notifySafely(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33
            ) {
                manager.notify(id, notification)
            }
        } catch (security: SecurityException) {
            Log.w(TAG, "通知权限未授予", security)
        }
    }

    override fun onDestroy() {
        sensorSource?.release()
        sensorSource = null
        parkingEngine = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ParkingTrackerService"
        const val CHANNEL_TRACKING = "parking_tracking"
        const val CHANNEL_RESULT = "parking_result"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_RESULT = 1002
        private const val MAX_TRACKING_MS = 10 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ParkingTrackerService::class.java)
            )
        }
    }
}
