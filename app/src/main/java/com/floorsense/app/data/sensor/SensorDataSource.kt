package com.floorsense.app.data.sensor

import com.floorsense.app.data.model.SensorFrame
import kotlinx.coroutines.flow.Flow

/**
 * 传感器数据流统一接口。
 *
 * 真实硬件实现为 [HardwareSensorSource]，纯 JVM 可测试的合成波形实现为 [MockSensorSource]，
 * 两者在引擎/ViewModel 侧完全可互换（见规格书 §工程铁律 3：Simulator First）。
 */
interface SensorDataSource {
    fun observeSensorFrames(): Flow<SensorFrame>
}
