package com.floorsense.app.domain.filter

import com.floorsense.app.data.model.SensorFrame
import java.util.ArrayDeque
import kotlin.math.abs

enum class MotionMode {
    STATIONARY,      // 静止或平地微动（锁死高度基准，吸收天气气压漂移）
    ELEVATOR,        // 电梯垂直升降中（检测到超重/失重加速度脉冲 + 显著 dP/dt）
    STAIRS_OR_RAMP   // 爬楼梯或汽车下地库斜坡（连续步伐或持续中等 dP/dt）
}

data class FilteredSensorState(
    val timestampMs: Long,
    val rawPressureHpa: Float,
    val smoothPressureHpa: Float,
    val pressureRateHpaPerSec: Float, // dP/dt (hPa/s)，负值代表上升，正值代表下降
    val verticalSpeedMps: Float,      // 估算垂直速度 (m/s)
    val accZVariance: Float,          // 最近窗口内 Z 轴线性加速度方差
    val motionMode: MotionMode
)

/**
 * 气压一阶低通滤波（EMA）+ 垂直运动状态门控识别器。
 *
 * 参数依据规格书 §3.2：
 * - EMA α = 0.18 抑制传感器高频毛刺；
 * - 2 秒滑动窗口计算 dP/dt 与 Z 轴加速度方差；
 * - |a_z| > 0.35 m/s² 触发电梯超重/失重脉冲，授予 12 秒电梯惯性延续窗口；
 * - 电梯判定的气压速率阈值为 0.07（有脉冲惯性）/ 0.14（无惯性）hPa/s；
 * - 楼梯/坡道判定为持续 0.05 hPa/s，或 2 秒内 ≥ 2 步且 0.035 hPa/s。
 */
class VerticalMotionGate(
    private val windowSize: Int = 20 // 10Hz 下对应 2.0 秒滑动窗口
) {
    private var smoothP: Float = Float.NaN
    private val historyWindow = ArrayDeque<SensorFrame>(windowSize)
    private var lastElevatorTriggerTimeMs: Long = 0L

    fun process(frame: SensorFrame): FilteredSensorState {
        // 1. 指数移动平均滤波 (EMA, alpha = 0.18) 抑制传感器高频毛刺
        smoothP = if (smoothP.isNaN()) {
            frame.pressureHpa
        } else {
            0.82f * smoothP + 0.18f * frame.pressureHpa
        }

        if (historyWindow.size >= windowSize) {
            historyWindow.removeFirst()
        }
        historyWindow.addLast(frame.copy(pressureHpa = smoothP))

        // 2. 计算 2 秒时间窗内的气压变化率 dP/dt 与 Z 轴加速度方差
        val oldest = historyWindow.first()
        val dtSec = ((frame.timestampMs - oldest.timestampMs) / 1000f).coerceAtLeast(0.1f)
        val dPdt = if (historyWindow.size >= 5) (smoothP - oldest.pressureHpa) / dtSec else 0f
        val verticalSpeedMps = -8.43f * dPdt

        val meanAccZ = historyWindow.map { it.linearAccZ }.average().toFloat()
        val accZVar = historyWindow.map { (it.linearAccZ - meanAccZ) * (it.linearAccZ - meanAccZ) }
            .average().toFloat()
        val recentSteps = historyWindow.count { it.stepDetected }

        // 3. 识别电梯超重/失重脉冲 (|a_z| > 0.35 m/s^2 触发后赋予 12 秒电梯惯性延续窗口)
        if (abs(frame.linearAccZ) > 0.35f) {
            lastElevatorTriggerTimeMs = frame.timestampMs
        }
        val inElevatorMomentum = (frame.timestampMs - lastElevatorTriggerTimeMs) <= 12_000L

        // 4. 综合判定 MotionMode
        val mode = when {
            // 电梯模式：刚经历超重/失重脉冲且气压快速变化 (|v_z| > 0.6 m/s)，或气压极速变化 (|v_z| > 1.2 m/s)
            (inElevatorMomentum && abs(dPdt) > 0.07f) || abs(dPdt) > 0.14f -> MotionMode.ELEVATOR

            // 楼梯或汽车坡道模式：有步伐且气压持续变化，或无步伐但处于持续稳定斜坡气压变化 (0.04 ~ 0.14 hPa/s)
            (recentSteps >= 2 && abs(dPdt) > 0.035f) || abs(dPdt) > 0.05f -> MotionMode.STAIRS_OR_RAMP

            // 静止/平地模式：无明显垂直速度，且加速度平稳
            else -> MotionMode.STATIONARY
        }

        return FilteredSensorState(
            timestampMs = frame.timestampMs,
            rawPressureHpa = frame.pressureHpa,
            smoothPressureHpa = smoothP,
            pressureRateHpaPerSec = dPdt,
            verticalSpeedMps = verticalSpeedMps,
            accZVariance = accZVar,
            motionMode = mode
        )
    }

    fun reset() {
        smoothP = Float.NaN
        historyWindow.clear()
        lastElevatorTriggerTimeMs = 0L
    }
}
