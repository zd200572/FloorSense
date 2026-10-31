package com.floorsense.app.data.model

/**
 * 经过时间戳对齐后的传感器采样帧（气压计 10Hz 即每 100ms 触发一次发射）。
 *
 * 设计约束（见开发规格书 §工程铁律 1/2）：
 * - 气压只携带原始值，所有平滑在 [com.floorsense.app.domain.filter] 内完成；
 * - linearAccZ 必须是去除重力后的垂直分量（世界坐标系），由数据源负责投影；
 * - stepDetected 表示当前 100ms 时间片内是否触发了 TYPE_STEP_DETECTOR。
 */
data class SensorFrame(
    val timestampMs: Long,
    val pressureHpa: Float,           // 原始气压值 (hPa / mbar)
    val linearAccZ: Float,            // 去除重力后的垂直线性加速度 (m/s^2)
    val stepDetected: Boolean,        // 当前时间片内是否触发了 STEP_DETECTOR
    val hasGpsSignal: Boolean = false // 是否具备有效室外 GPS 信号（用于辅助判断回到地面 1F）
)
