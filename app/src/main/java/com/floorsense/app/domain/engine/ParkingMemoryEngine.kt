package com.floorsense.app.domain.engine

import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.domain.filter.FilteredSensorState
import com.floorsense.app.domain.filter.MotionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import kotlin.math.roundToInt

enum class ParkingTrackPhase {
    IDLE_IN_VEHICLE,           // 驾驶中，维护 5 分钟环形气压缓存
    PARKED_WAITING_ASCENT,     // 已断开车载蓝牙/停车，锁定车位气压 P_car，等待用户乘电梯上楼
    ASCENDING_TO_GROUND,       // 监测到用户正在乘电梯/走楼梯上升
    SETTLED                    // 已到达 1F 大厅完成结算，锁定最终地下层数
}

data class ParkingMemoryState(
    val phase: ParkingTrackPhase = ParkingTrackPhase.IDLE_IN_VEHICLE,
    val estimatedFloorLabel: String? = null, // 如 "B2", "B3"
    val depthMeters: Float = 0f,             // 距离地面垂直深度 (如 -7.5m)
    val parkedTimestampMs: Long? = null,
    val isConfirmedByReturnAscent: Boolean = false // true=已通过回1F电梯精确确认，false=仅靠进库下坡预估
)

/**
 * 解决痛点：地下车库无 GPS，通过【进库下坡环形缓存回溯】+【下车后乘电梯回 1F 双锚点比对】
 * 精准锁定 B1/B2/B3（规格书 §3.4）。
 *
 * 锚点 1（onVehicleParked，车载蓝牙断开瞬间）：回溯最近 3 分钟环形缓存中的最低气压
 * （即进地库斜坡前的地面气压），得到初步层数预估；
 * 锚点 2（回 1F 电梯行程 + 恢复静止/GPS）：取整个上升过程的峰值气压落差 8.43·ΔP 结算最终层数。
 */
class ParkingMemoryEngine(
    private var profile: BuildingProfile = BuildingProfile.OFFICE
) {
    // 缓存最近 5 分钟的气压采样 (每 1 秒存入一个抽样点，容量 300)
    private val drivingRingBuffer = ArrayDeque<Pair<Long, Float>>(300)
    private var lastSampledSec: Long = 0L

    private var carParkedPressureHpa: Float = Float.NaN
    private var peakAscentDropHpa: Float = 0f

    // 结算鲁棒性：GPS 出现立即结算；无 GPS 时需要连续 2 秒确认恢复平地静止，
    // 避免电梯短暂平层停顿被误判为“已到达地面”。
    private var stationaryStreakCount: Int = 0

    private val _parkingState = MutableStateFlow(ParkingMemoryState())
    val parkingState: StateFlow<ParkingMemoryState> = _parkingState.asStateFlow()

    fun onSensorFrame(filtered: FilteredSensorState, hasGps: Boolean = false) {
        val currentSec = filtered.timestampMs / 1000L
        if (currentSec > lastSampledSec) {
            lastSampledSec = currentSec
            if (drivingRingBuffer.size >= 300) drivingRingBuffer.removeFirst()
            drivingRingBuffer.addLast(filtered.timestampMs to filtered.smoothPressureHpa)
        }

        if (filtered.motionMode == MotionMode.STATIONARY) {
            stationaryStreakCount++
        } else {
            stationaryStreakCount = 0
        }

        when (_parkingState.value.phase) {
            ParkingTrackPhase.IDLE_IN_VEHICLE -> {
                // 等待车载蓝牙断开或手动触发 onVehicleParked()
            }

            ParkingTrackPhase.PARKED_WAITING_ASCENT -> {
                // 检测用户是否开始乘电梯或爬楼离开地库（气压比车位气压下降超过 0.35 hPa，即上升 > 3米）
                val dropFromCar = carParkedPressureHpa - filtered.smoothPressureHpa
                if (dropFromCar > 0.35f && filtered.motionMode != MotionMode.STATIONARY) {
                    peakAscentDropHpa = dropFromCar
                    _parkingState.value = _parkingState.value.copy(
                        phase = ParkingTrackPhase.ASCENDING_TO_GROUND
                    )
                }
            }

            ParkingTrackPhase.ASCENDING_TO_GROUND -> {
                val dropFromCar = carParkedPressureHpa - filtered.smoothPressureHpa
                if (dropFromCar > peakAscentDropHpa) {
                    peakAscentDropHpa = dropFromCar
                }

                // 当用户离开电梯恢复平地静止（连续 2 秒），或者室外 GPS 信号重新出现时，
                // 结算 1F 地面气压差
                if (hasGps || stationaryStreakCount >= SETTLE_STATIONARY_FRAMES) {
                    val totalAscentMeters = 8.43f * peakAscentDropHpa
                    val floorIdx = mapDepthToBasementIndex(totalAscentMeters)
                    _parkingState.value = _parkingState.value.copy(
                        phase = ParkingTrackPhase.SETTLED,
                        estimatedFloorLabel = FloorStateMachine.formatFloorLabel(floorIdx),
                        depthMeters = -totalAscentMeters,
                        isConfirmedByReturnAscent = true
                    )
                }
            }

            ParkingTrackPhase.SETTLED -> Unit
        }
    }

    /**
     * 当检测到车载蓝牙断开 (ACL_DISCONNECTED) 或用户点击“记录车位”时调用。
     *
     * 立即回溯前 3 分钟 RingBuffer 中的最低气压（即进地库斜坡前的地面气压）做初步预估。
     */
    fun onVehicleParked(currentTimeMs: Long, currentSmoothPressureHpa: Float) {
        carParkedPressureHpa = currentSmoothPressureHpa
        peakAscentDropHpa = 0f
        stationaryStreakCount = 0

        val threeMinAgo = currentTimeMs - 180_000L
        val minPressureBeforeRamp = drivingRingBuffer
            .filter { it.first >= threeMinAgo }
            .minOfOrNull { it.second } ?: currentSmoothPressureHpa

        val rampPressureRise = (currentSmoothPressureHpa - minPressureBeforeRamp).coerceAtLeast(0f)
        val initialDepthMeters = 8.43f * rampPressureRise
        val initialFloorIdx = mapDepthToBasementIndex(initialDepthMeters)

        _parkingState.value = ParkingMemoryState(
            phase = ParkingTrackPhase.PARKED_WAITING_ASCENT,
            estimatedFloorLabel = FloorStateMachine.formatFloorLabel(initialFloorIdx),
            depthMeters = -initialDepthMeters,
            parkedTimestampMs = currentTimeMs,
            isConfirmedByReturnAscent = false
        )
    }

    /** 切换建筑层高模板（如用户在设置里从写字楼改为住宅） */
    fun updateProfile(newProfile: BuildingProfile) {
        profile = newProfile
    }

    fun reset() {
        drivingRingBuffer.clear()
        lastSampledSec = 0L
        carParkedPressureHpa = Float.NaN
        peakAscentDropHpa = 0f
        stationaryStreakCount = 0
        _parkingState.value = ParkingMemoryState()
    }

    private fun mapDepthToBasementIndex(depthM: Float): Int {
        if (depthM < 2.2f) return 0 // 仍在地面 1F 停车场
        val depthBelowB1 = depthM - profile.basementFirstHeightM
        return if (depthBelowB1 <= 1.2f) {
            -1 // B1
        } else {
            -1 - (depthBelowB1 / profile.basementSubHeightM).roundToInt()
        }
    }

    companion object {
        // 10Hz 采样下连续 200 帧 ≈ 20 秒静止确认（电梯平层停顿只有数秒，不会误触发）
        private const val SETTLE_STATIONARY_FRAMES = 200
    }
}
