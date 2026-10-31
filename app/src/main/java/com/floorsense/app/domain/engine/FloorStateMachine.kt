package com.floorsense.app.domain.engine

import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.domain.filter.FilteredSensorState
import com.floorsense.app.domain.filter.MotionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class FloorUiState(
    val currentFloorIndex: Int = 0,         // 0 代表 1F, 1 代表 2F, -2 代表 B2
    val currentFloorLabel: String = "1F",
    val relativeAltitudeM: Float = 0f,      // 相对 1F 基准面的高度 (m)
    val basePressureHpa: Float = 1013.25f,  // 当前动态锁定的 1F 基准气压
    val currentPressureHpa: Float = 1013.25f,
    val motionMode: MotionMode = MotionMode.STATIONARY
)

/**
 * 实时抗干扰楼层状态机（规格书 §3.3）。
 *
 * 两条核心铁律的实现：
 * 1. 禁止气压裸算绝对海拔——高度一律来自相对气压差 ΔP 与动态基准锚点 P_base；
 * 2. 运动门控防漂移——STATIONARY 时锁死 lockedAltitudeM，环境气压的缓慢波动
 *    （天气/空调新风/开关门）通过反向修正 basePressure1F 被吸收；
 *    ELEVATOR / STAIRS_OR_RAMP 激活态则冻结基准，把 ΔP 实时换算为高度。
 */
class FloorStateMachine(
    private var profile: BuildingProfile = BuildingProfile.OFFICE
) {
    private var basePressure1F: Float = Float.NaN
    private var lockedAltitudeM: Float = 0f

    private val _state = MutableStateFlow(FloorUiState())
    val state: StateFlow<FloorUiState> = _state.asStateFlow()

    /**
     * 手动或通过已知锚点（如回到地面 GPS 恢复、连上家里 Wi-Fi）强制校准当前楼层。
     * 根据当前高度反推 1F 基准气压: P_1F = P_current + (h / 8.43)
     */
    fun calibrateCurrentFloor(floorIndex: Int, currentSmoothPressureHpa: Float) {
        val altitudeOffset = floorIndexToAltitude(floorIndex)
        lockedAltitudeM = altitudeOffset
        basePressure1F = currentSmoothPressureHpa + (altitudeOffset / 8.43f)
        emitState(currentSmoothPressureHpa, MotionMode.STATIONARY)
    }

    fun updateProfile(newProfile: BuildingProfile) {
        profile = newProfile
        emitState(_state.value.currentPressureHpa, _state.value.motionMode)
    }

    fun update(filtered: FilteredSensorState) {
        val p = filtered.smoothPressureHpa
        if (basePressure1F.isNaN()) {
            basePressure1F = p
            lockedAltitudeM = 0f
        }

        when (filtered.motionMode) {
            MotionMode.STATIONARY -> {
                // 【核心防漂移逻辑】：静止时高度锁定为 lockedAltitudeM 不变。
                // 任何环境气压缓慢波动（台风、空调新风），通过反向修正 basePressure1F 予以吸收！
                val expectedBaseP = p + (lockedAltitudeM / 8.43f)
                basePressure1F = 0.98f * basePressure1F + 0.02f * expectedBaseP
            }
            MotionMode.ELEVATOR, MotionMode.STAIRS_OR_RAMP -> {
                // 垂直运动激活态：冻结 basePressure1F，将气压差实时转换为高度变化
                lockedAltitudeM = -8.43f * (p - basePressure1F)
            }
        }

        emitState(p, filtered.motionMode)
    }

    fun reset() {
        basePressure1F = Float.NaN
        lockedAltitudeM = 0f
        _state.value = FloorUiState()
    }

    /**
     * 恢复持久化的锚点快照（应用重启后的记忆机制）：
     * 直接注入上次退出时的 1F 基准气压与锁定高度，调用方需自行保证快照新鲜度。
     */
    fun restoreAnchor(basePressure1FHpa: Float, lockedAltitudeMeters: Float) {
        basePressure1F = basePressure1FHpa
        lockedAltitudeM = lockedAltitudeMeters
        emitState(_state.value.currentPressureHpa, MotionMode.STATIONARY)
    }

    private fun emitState(currentP: Float, mode: MotionMode) {
        val floorIdx = altitudeToFloorIndex(lockedAltitudeM)
        _state.value = FloorUiState(
            currentFloorIndex = floorIdx,
            currentFloorLabel = formatFloorLabel(floorIdx),
            relativeAltitudeM = lockedAltitudeM,
            basePressureHpa = basePressure1F,
            currentPressureHpa = currentP,
            motionMode = mode
        )
    }

    private fun altitudeToFloorIndex(altM: Float): Int {
        return if (altM >= -1.8f) {
            // 地上楼层计算
            (altM / profile.standardFloorHeightM).roundToInt()
        } else {
            // 地下楼层计算（扣除首层 B1 落差后，按地库标准层高算）
            val depthBelowB1 = (-altM) - profile.basementFirstHeightM
            if (depthBelowB1 <= 1.2f) {
                -1 // B1
            } else {
                -1 - (depthBelowB1 / profile.basementSubHeightM).roundToInt()
            }
        }
    }

    private fun floorIndexToAltitude(floorIndex: Int): Float {
        return when {
            floorIndex == 0 -> 0f
            floorIndex > 0 -> floorIndex * profile.standardFloorHeightM
            floorIndex == -1 -> -profile.basementFirstHeightM
            else -> -(profile.basementFirstHeightM + (-floorIndex - 1) * profile.basementSubHeightM)
        }
    }

    companion object {
        /**
         * 楼层命名映射（跳过 0 层，规格书 §2.2）：
         * floorIndex = 0 -> "1F"；floorIndex = 1 -> "2F"；floorIndex = -2 -> "B2"。
         */
        fun formatFloorLabel(floorIndex: Int): String {
            return if (floorIndex >= 0) "${floorIndex + 1}F" else "B${-floorIndex}"
        }
    }
}
