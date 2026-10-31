package com.floorsense.app.data.sensor

import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.model.SensorFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/**
 * 合成波形发生器（规格书 §工程铁律 3：Simulator First）。
 *
 * 纯 Kotlin 实现、零 Android 依赖，可在桌面 JVM 上复现真实场景：
 * 台风天气漂移、平地步行、电梯升降（含超重/失重脉冲）、汽车下地库斜坡等。
 * Dashboard 的“模拟器注入控制台”与单元测试都基于本数据源驱动。
 *
 * 物理换算：Δh = -8.43 · ΔP，即上升 1 米气压约下降 0.1186 hPa。
 */
class MockSensorSource(
    private val sampleIntervalMs: Long = 100L,
    private val addNoise: Boolean = true,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SensorDataSource {

    private val _frames = MutableSharedFlow<SensorFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun observeSensorFrames(): Flow<SensorFrame> = _frames

    private var virtualTimeMs: Long = System.currentTimeMillis()
    private var currentPressureHpa: Float = DEFAULT_GROUND_PRESSURE
    private val random = Random(System.nanoTime())
    private var playingJob: Job? = null

    /** 设置场景起始地面气压（1F 基准，默认 1013.25 hPa） */
    fun setBasePressure(hpa: Float) {
        currentPressureHpa = hpa
    }

    /** UI 控制台手动注入单帧（如滑杆拖动气压） */
    fun emitManual(pressureHpa: Float, accZ: Float = 0.02f, step: Boolean = false, gps: Boolean = false) {
        virtualTimeMs += sampleIntervalMs
        currentPressureHpa = pressureHpa
        _frames.tryEmit(
            SensorFrame(
                timestampMs = virtualTimeMs,
                pressureHpa = pressureHpa,
                linearAccZ = accZ,
                stepDetected = step,
                hasGpsSignal = gps
            )
        )
    }

    /** 异步播放一段合成场景（实时速率，供 UI 演示） */
    fun playScenario(steps: List<MockStep>, startGroundPressureHpa: Float = DEFAULT_GROUND_PRESSURE) {
        stopScenario()
        currentPressureHpa = startGroundPressureHpa
        playingJob = externalScope.launch {
            for (step in steps) {
                playStep(step)
            }
        }
    }

    fun stopScenario() {
        playingJob?.cancel()
        playingJob = null
    }

    private suspend fun playStep(step: MockStep) {
        val frameCount = (step.durationMs / sampleIntervalMs).toInt().coerceAtLeast(1)

        var pressureRatePerFrame = 0f
        var baseAccZ = 0.02f
        var gps = false
        var stepEveryN = 0
        var pulseStartFrames = 0
        var pulseEndFrames = 0
        var pulseSign = 1f

        when (step) {
            is MockStep.Hold -> {
                // 静止：可选的天气缓慢漂移（台风过境 / 空调新风）
                pressureRatePerFrame = step.totalPressureDriftHpa / frameCount
                baseAccZ = step.accZ
                gps = step.gps
            }
            is MockStep.Walk -> {
                // 平地步行：步伐按频率触发，气压不变
                val stepsPerFrame = step.stepsPerSecond * sampleIntervalMs / 1000f
                stepEveryN = if (stepsPerFrame <= 0f) 0 else (1f / stepsPerFrame).toInt().coerceAtLeast(1)
                baseAccZ = step.accZ
            }
            is MockStep.ElevatorRide -> {
                // 电梯：气压按楼层落差换算，启动/减速段叠加超重/失重脉冲
                val deltaAltM = floorIndexToAltitude(step.toFloorIndex, step.profile) -
                        floorIndexToAltitude(step.fromFloorIndex, step.profile)
                pressureRatePerFrame = -deltaAltM / 8.43f / frameCount
                pulseStartFrames = (frameCount * PULSE_FRACTION).toInt().coerceAtLeast(3)
                pulseEndFrames = (frameCount * PULSE_FRACTION).toInt().coerceAtLeast(3)
                pulseSign = if (deltaAltM >= 0f) 1f else -1f // 上升启动为超重(+)，下降启动为失重(-)
            }
            is MockStep.VehicleRamp -> {
                // 汽车坡道：持续稳定的中等气压变化率，无步伐（驾驶中 STEP_DETECTOR 不触发）
                val signedDepth = if (step.downward) step.depthMeters else -step.depthMeters
                pressureRatePerFrame = signedDepth / 8.43f / frameCount
                baseAccZ = step.accZ
            }
            is MockStep.StairClimb -> {
                // 爬楼：持续步伐 + 缓慢气压变化（变化率介于坡道与电梯之间）
                val signedAlt = if (step.upward) step.altitudeMeters else -step.altitudeMeters
                pressureRatePerFrame = -signedAlt / 8.43f / frameCount
                val stepsPerFrame = step.stepsPerSecond * sampleIntervalMs / 1000f
                stepEveryN = if (stepsPerFrame <= 0f) 0 else (1f / stepsPerFrame).toInt().coerceAtLeast(1)
                baseAccZ = step.accZ
            }
        }

        repeat(frameCount) { i ->
            val accZ = when {
                i < pulseStartFrames -> pulseSign * 0.8f          // 启动脉冲
                i >= frameCount - pulseEndFrames -> -pulseSign * 0.8f // 减速反向脉冲
                else -> baseAccZ
            }

            val pressureNoise = if (addNoise) ((random.nextDouble() - 0.5) * 0.04).toFloat() else 0f
            val accNoise = if (addNoise) ((random.nextDouble() - 0.5) * 0.02).toFloat() else 0f

            _frames.tryEmit(
                SensorFrame(
                    timestampMs = virtualTimeMs,
                    pressureHpa = currentPressureHpa + pressureNoise,
                    linearAccZ = accZ + accNoise,
                    stepDetected = stepEveryN > 0 && i % stepEveryN == 0,
                    hasGpsSignal = gps
                )
            )

            currentPressureHpa += pressureRatePerFrame
            virtualTimeMs += sampleIntervalMs
            delay(sampleIntervalMs)
        }
    }

    /** 楼层内部索引 -> 相对 1F 基准面的高度（与 FloorStateMachine 的映射保持一致） */
    private fun floorIndexToAltitude(floorIndex: Int, profile: BuildingProfile): Float = when {
        floorIndex == 0 -> 0f
        floorIndex > 0 -> floorIndex * profile.standardFloorHeightM
        floorIndex == -1 -> -profile.basementFirstHeightM
        else -> -(profile.basementFirstHeightM + (-floorIndex - 1) * profile.basementSubHeightM)
    }

    /**
     * 场景步骤定义。
     */
    sealed class MockStep {
        abstract val durationMs: Long

        /** 静止/平地驻留；[totalPressureDriftHpa] 模拟天气（台风）或新风系统造成的缓慢漂移 */
        data class Hold(
            override val durationMs: Long,
            val totalPressureDriftHpa: Float = 0f,
            val accZ: Float = 0.02f,
            val gps: Boolean = false
        ) : MockStep()

        /** 平地步行（产生连续 STEP_DETECTOR 触发） */
        data class Walk(
            override val durationMs: Long,
            val stepsPerSecond: Int = 2,
            val accZ: Float = 0.15f
        ) : MockStep()

        /** 电梯升降：内部楼层索引 [fromFloorIndex] -> [toFloorIndex]，含超重/失重脉冲 */
        data class ElevatorRide(
            override val durationMs: Long,
            val fromFloorIndex: Int,
            val toFloorIndex: Int,
            val profile: BuildingProfile = BuildingProfile.OFFICE
        ) : MockStep()

        /** 汽车沿地库坡道行驶：下潜/爬升 [depthMeters] 米 */
        data class VehicleRamp(
            override val durationMs: Long,
            val depthMeters: Float,
            val downward: Boolean = true,
            val accZ: Float = 0.15f
        ) : MockStep()

        /** 爬楼梯：连续步伐叠加缓慢气压变化（[upward]=false 表示下楼） */
        data class StairClimb(
            override val durationMs: Long,
            val altitudeMeters: Float,
            val upward: Boolean = true,
            val stepsPerSecond: Int = 2,
            val accZ: Float = 0.3f
        ) : MockStep()
    }

    companion object {
        const val DEFAULT_GROUND_PRESSURE = 1013.25f
        private const val PULSE_FRACTION = 0.12f

        /** 校验楼层索引是否为合法内部表示（0=1F，正=地上，负=地下） */
        fun isValidFloorIndex(index: Int): Boolean = abs(index) <= 20
    }
}
