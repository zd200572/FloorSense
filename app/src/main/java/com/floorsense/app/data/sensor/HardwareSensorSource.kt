package com.floorsense.app.data.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.floorsense.app.data.model.SensorFrame
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.sqrt

/**
 * Android SensorManager 真实硬件实现。
 *
 * 数据管线：
 * - TYPE_PRESSURE @ 10Hz —— 主时钟，驱动 SensorFrame 发射节拍；
 * - TYPE_LINEAR_ACCELERATION + TYPE_GRAVITY —— 将线性加速度投影到重力方向，
 *   得到与设备姿态无关的“垂直”分量 linearAccZ；
 * - TYPE_STEP_DETECTOR —— 100ms 时间片内的步伐事件（需 ACTIVITY_RECOGNITION 运行时权限）；
 * - GPS 有效性 —— 通过最近一次定位 fix 的新鲜度（60 秒内）近似判断是否回到室外地面。
 */
class HardwareSensorSource(context: Context) : SensorDataSource {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensorThread = HandlerThread("floorsense-sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    // 传感器回调线程写入，采集协程读取
    @Volatile private var latestAccZ: Float = 0f
    @Volatile private var gravityVector: FloatArray = floatArrayOf(0f, 0f, 9.81f)
    @Volatile private var pendingStep: Boolean = false

    override fun observeSensorFrames(): Flow<SensorFrame> = callbackFlow {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        gravityVector = event.values.copyOf()
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        latestAccZ = projectOntoGravity(event.values)
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        pendingStep = true
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val step = pendingStep
                        pendingStep = false
                        trySend(
                            SensorFrame(
                                timestampMs = event.timestamp / 1_000_000L, // ns -> ms（单调时钟）
                                pressureHpa = event.values[0],
                                linearAccZ = latestAccZ,
                                stepDetected = step,
                                hasGpsSignal = hasFreshGpsFix()
                            )
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressure == null) {
            close(IllegalStateException("本设备没有气压计，无法进行楼层定位"))
            return@callbackFlow
        }

        sensorManager.registerListener(listener, pressure, PRESSURE_PERIOD_US, sensorHandler)
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(listener, it, MOTION_PERIOD_US, sensorHandler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(listener, it, MOTION_PERIOD_US, sensorHandler)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }.conflate()

    /** 将设备坐标系的线性加速度投影到重力方向，得到垂直分量 */
    private fun projectOntoGravity(linearAcc: FloatArray): Float {
        val g = gravityVector
        val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (norm < 1e-3f) return linearAcc[2] // 无重力数据时退化为设备 Z 轴
        return (linearAcc[0] * g[0] + linearAcc[1] * g[1] + linearAcc[2] * g[2]) / norm
    }

    /** 最近 60 秒内存在 GPS/网络 fix 视为“室外有信号” */
    @SuppressLint("MissingPermission")
    private fun hasFreshGpsFix(): Boolean {
        return try {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            last != null && (System.currentTimeMillis() - last.time) < GPS_FIX_FRESH_MS
        } catch (security: SecurityException) {
            false
        } catch (runtime: RuntimeException) {
            false
        }
    }

    /** 服务销毁时释放后台传感器线程 */
    fun release() {
        sensorThread.quitSafely()
    }

    private companion object {
        const val PRESSURE_PERIOD_US = 100_000   // 10Hz
        const val MOTION_PERIOD_US = 20_000      // 50Hz
        const val GPS_FIX_FRESH_MS = 60_000L
    }
}
