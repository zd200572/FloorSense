package com.floorsense.app.domain.filter

import kotlin.math.sqrt

/**
 * 一维气压卡尔曼滤波器（标量状态：气压 hPa）。
 *
 * 用途：Dashboard 波形图的显示级平滑（决策路径的滤波在 [VerticalMotionGate] 内完成，
 * 两者职责分离——显示链路允许更平滑的延迟，决策链路严格按规格书的 EMA 参数执行）。
 *
 * 数学形式（predict/update）：
 *   predict:  P⁻ = P + Q                （过程噪声 Q：真实气压缓慢变化的容差）
 *   update:   K  = P⁻ / (P⁻ + R)        （测量噪声 R：气压计高频毛刺方差）
 *             x̂  = x̂⁻ + K · (z − x̂⁻)
 *             P  = (1 − K) · P⁻
 *
 * 稳态增益 K_ss = sqrt(R·Q) / (sqrt(R·Q) + R)，默认参数下 K_ss ≈ 0.17，
 * 与规格书决策链路的 EMA(α = 0.18) 平滑强度同量级。
 */
class BaroKalmanFilter(
    private val processNoiseQ: Double = 1e-4,      // hPa^2 / step
    private val measurementNoiseR: Double = 2.5e-3 // hPa^2
) {
    private var estimate: Double = Double.NaN
    private var errorCovariance: Double = 1.0

    /** 最近一次更新的卡尔曼增益，用于诊断/波形着色 */
    var lastGain: Double = Double.NaN
        private set

    val isInitialized: Boolean get() = !estimate.isNaN()

    fun process(rawPressureHpa: Float): Float {
        if (estimate.isNaN()) {
            estimate = rawPressureHpa.toDouble()
            errorCovariance = measurementNoiseR
            lastGain = 1.0
            return rawPressureHpa
        }

        // predict
        val priorCovariance = errorCovariance + processNoiseQ
        // update
        val gain = priorCovariance / (priorCovariance + measurementNoiseR)
        val innovation = rawPressureHpa - estimate
        estimate += gain * innovation
        errorCovariance = (1.0 - gain) * priorCovariance
        lastGain = gain

        return estimate.toFloat()
    }

    fun reset() {
        estimate = Double.NaN
        errorCovariance = 1.0
        lastGain = Double.NaN
    }

    /** 理论稳态增益，供测试与诊断断言使用 */
    fun steadyStateGain(): Double {
        val p = sqrt(measurementNoiseR * processNoiseQ)
        return p / (p + measurementNoiseR)
    }
}
