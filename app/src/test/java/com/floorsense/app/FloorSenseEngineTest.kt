package com.floorsense.app

import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.model.SensorFrame
import com.floorsense.app.domain.engine.FloorStateMachine
import com.floorsense.app.domain.engine.ParkingMemoryEngine
import com.floorsense.app.domain.engine.ParkingTrackPhase
import com.floorsense.app.domain.filter.VerticalMotionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorSenseEngineTest {

    /**
     * 测试场景 1：静坐办公室遭遇台风过境（气压在 10 分钟内大幅下降 2.5 hPa，相当于高度上升 21 米），
     * 验证运动门控能锁死楼层在 1F，绝不发生误跳层。
     */
    @Test
    fun `stationary weather pressure drop should NOT change floor`() {
        val gate = VerticalMotionGate()
        val floorEngine = FloorStateMachine(BuildingProfile.OFFICE)

        var timeMs = 0L
        var pressure = 1013.0f

        // 模拟 600 秒（6000 帧，10Hz），气压缓慢下降 2.5 hPa，但加速度计无波动、无步数
        repeat(6000) {
            timeMs += 100L
            pressure -= (2.5f / 6000f)
            val frame = SensorFrame(
                timestampMs = timeMs,
                pressureHpa = pressure,
                linearAccZ = 0.02f, // 极小噪声
                stepDetected = false
            )
            floorEngine.update(gate.process(frame))
        }

        assertEquals("1F", floorEngine.state.value.currentFloorLabel)
        assertEquals(0, floorEngine.state.value.currentFloorIndex)
    }

    /**
     * 测试场景 2：地下停车记忆完整闭环（开车下坡进 B3 -> 蓝牙断开 -> 走去电梯厅 -> 坐电梯回 1F 大厅）
     */
    @Test
    fun `underground parking B3 ramp descent and elevator return settlement`() {
        val gate = VerticalMotionGate()
        val parkingEngine = ParkingMemoryEngine(BuildingProfile.OFFICE) // B1=4.5m, B2/B3=3.4m -> B3 约 -11.3m (+1.34 hPa)

        var timeMs = 0L
        var pressure = 1010.00f // 1F 地面初始气压

        // 1. 地面正常行驶 30 秒 (300 帧)
        repeat(300) {
            timeMs += 100L
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, 0.05f, false)))
        }

        // 2. 沿地库盘旋斜坡向下开往 B3（耗时 25 秒，气压上升 1.34 hPa，对应下潜约 11.3 米）
        repeat(250) {
            timeMs += 100L
            pressure += (1.34f / 250f)
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, 0.15f, false)))
        }

        // 3. 停稳熄火，车载蓝牙断开触发锚点 1
        parkingEngine.onVehicleParked(timeMs, pressure)
        // 验证仅靠斜坡回溯就能初步预估出 B3
        assertEquals("B3", parkingEngine.parkingState.value.estimatedFloorLabel)

        // 4. 在 B3 走去电梯厅（平地行走 10 秒，气压不变）
        repeat(100) {
            timeMs += 100L
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, 0.2f, it % 5 == 0)))
        }

        // 5. 乘电梯从 B3 升回 1F（耗时 8 秒，伴随启动超重脉冲，气压下降 1.34 hPa 回到 1010.00 hPa）
        repeat(80) { step ->
            timeMs += 100L
            pressure -= (1.34f / 80f)
            val elevatorAccZ = when {
                step < 10 -> 0.8f   // 电梯启动超重
                step > 70 -> -0.8f  // 电梯减速失重
                else -> 0.05f
            }
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, elevatorAccZ, false)))
        }

        // 6. 走出 1F 电梯厅，恢复静止且收获室外 GPS 信号（触发锚点 2 最终结算）
        repeat(30) {
            timeMs += 100L
            parkingEngine.onSensorFrame(
                filtered = gate.process(SensorFrame(timeMs, pressure, 0.02f, false, hasGpsSignal = true)),
                hasGps = true
            )
        }

        val finalState = parkingEngine.parkingState.value
        assertEquals(ParkingTrackPhase.SETTLED, finalState.phase)
        assertEquals("B3", finalState.estimatedFloorLabel)
        assertTrue(finalState.isConfirmedByReturnAscent)
    }
}
