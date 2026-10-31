package com.floorsense.app

import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.model.SensorFrame
import com.floorsense.app.data.sensor.MockSensorSource
import com.floorsense.app.domain.engine.FloorStateMachine
import com.floorsense.app.domain.engine.ParkingMemoryEngine
import com.floorsense.app.domain.engine.ParkingTrackPhase
import com.floorsense.app.domain.filter.BaroKalmanFilter
import com.floorsense.app.domain.filter.MotionMode
import com.floorsense.app.domain.filter.VerticalMotionGate
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规格书两个必过场景之外的补充回归测试：
 * 电梯/楼梯追踪、锚点校准防漂移、卡尔曼滤波收敛、楼层命名映射、模拟器波形。
 */
class FloorSenseExtraTest {

    /** 电梯从 1F 直达 6F（写字楼 4.0m 层高，净升 20m ≈ 2.372 hPa），运动门控应全程识别为 ELEVATOR */
    @Test
    fun `elevator ride 1F to 6F tracks floor correctly`() {
        val gate = VerticalMotionGate()
        val floorEngine = FloorStateMachine(BuildingProfile.OFFICE)

        var timeMs = 0L
        var pressure = 1013.25f
        var sawElevatorMode = false

        // 电梯上行 8 秒（80 帧）：启动超重 + 匀速 + 减速失重
        repeat(80) { step ->
            timeMs += 100L
            pressure -= (20.0f / 8.43f / 80f)
            val accZ = when {
                step < 8 -> 0.8f
                step > 72 -> -0.8f
                else -> 0.05f
            }
            val filtered = gate.process(SensorFrame(timeMs, pressure, accZ, false))
            if (filtered.motionMode == MotionMode.ELEVATOR) sawElevatorMode = true
            floorEngine.update(filtered)
        }

        assertTrue("电梯行程中必须出现 ELEVATOR 门控", sawElevatorMode)

        // 到站后静止 5 秒，楼层应锁定在内部索引 5（显示 6F）
        repeat(50) {
            timeMs += 100L
            floorEngine.update(gate.process(SensorFrame(timeMs, pressure, 0.02f, false)))
        }
        assertEquals(5, floorEngine.state.value.currentFloorIndex)
        assertEquals("6F", floorEngine.state.value.currentFloorLabel)
    }

    /** 爬楼梯上 1 层（4.0m / 13s，持续步伐），应识别 STAIRS_OR_RAMP 并正确显示 2F */
    @Test
    fun `stairs climb to 2F detected and tracked`() {
        val gate = VerticalMotionGate()
        val floorEngine = FloorStateMachine(BuildingProfile.OFFICE)

        var timeMs = 0L
        var pressure = 1013.25f
        var sawStairsMode = false

        val totalDrop = 4.0f / 8.43f // 一层楼对应气压落差
        repeat(130) { i ->
            timeMs += 100L
            pressure -= totalDrop / 130f
            val filtered = gate.process(SensorFrame(timeMs, pressure, 0.3f, i % 5 == 0))
            if (filtered.motionMode == MotionMode.STAIRS_OR_RAMP) sawStairsMode = true
            floorEngine.update(filtered)
        }
        assertTrue("爬楼过程必须出现 STAIRS_OR_RAMP 门控", sawStairsMode)

        // 登顶后静止锁定
        repeat(50) {
            timeMs += 100L
            floorEngine.update(gate.process(SensorFrame(timeMs, pressure, 0.02f, false)))
        }
        assertEquals(1, floorEngine.state.value.currentFloorIndex)
        assertEquals("2F", floorEngine.state.value.currentFloorLabel)
    }

    /** 锚点校准：强制校准到 B2 后，即使天气漂移也不应跳层 */
    @Test
    fun `calibrate to B2 absorbs weather drift`() {
        val gate = VerticalMotionGate()
        val floorEngine = FloorStateMachine(BuildingProfile.OFFICE)

        var timeMs = 0L
        var pressure = 1015.0f
        floorEngine.calibrateCurrentFloor(-2, pressure)
        assertEquals("B2", floorEngine.state.value.currentFloorLabel)

        // 校准后遭遇 0.5 hPa 天气降压（相当于“虚高”4.2m），必须被基准吸收
        repeat(3000) {
            timeMs += 100L
            pressure -= (0.5f / 3000f)
            floorEngine.update(gate.process(SensorFrame(timeMs, pressure, 0.02f, false)))
        }
        assertEquals("B2", floorEngine.state.value.currentFloorLabel)
    }

    /** 地面停车（未进地库）不应给出负楼层预估，也不会误入 ASCENDING */
    @Test
    fun `ground level parking stays on 1F without ascent`() {
        val gate = VerticalMotionGate()
        val parkingEngine = ParkingMemoryEngine(BuildingProfile.OFFICE)

        var timeMs = 0L
        var pressure = 1012.0f
        repeat(600) { // 地面行驶+停车后驻留 60 秒
            timeMs += 100L
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, 0.05f, false)))
        }

        parkingEngine.onVehicleParked(timeMs, pressure)
        val state = parkingEngine.parkingState.value
        assertEquals(ParkingTrackPhase.PARKED_WAITING_ASCENT, state.phase)
        assertEquals("1F", state.estimatedFloorLabel)

        // 之后持续静止，不得误判为已上升
        repeat(600) {
            timeMs += 100L
            parkingEngine.onSensorFrame(gate.process(SensorFrame(timeMs, pressure, 0.02f, false)))
        }
        assertEquals(ParkingTrackPhase.PARKED_WAITING_ASCENT, parkingEngine.parkingState.value.phase)
    }

    /** 自定义层高：3.2m/层，电梯上 5 层（16m）应正确显示 6F */
    @Test
    fun `custom floor height 3_2m maps 5 floors up to 6F`() {
        val gate = VerticalMotionGate()
        val floorEngine = FloorStateMachine(BuildingProfile("自定义", 3.2f, 4.2f, 3.3f))

        var timeMs = 0L
        var pressure = 1013.25f

        repeat(80) { step ->
            timeMs += 100L
            pressure -= (16.0f / 8.43f / 80f)
            val accZ = when {
                step < 8 -> 0.8f
                step > 72 -> -0.8f
                else -> 0.05f
            }
            floorEngine.update(gate.process(SensorFrame(timeMs, pressure, accZ, false)))
        }

        repeat(50) {
            timeMs += 100L
            floorEngine.update(gate.process(SensorFrame(timeMs, pressure, 0.02f, false)))
        }

        assertEquals("6F", floorEngine.state.value.currentFloorLabel)
        assertEquals(5, floorEngine.state.value.currentFloorIndex)
    }

    /** 楼层命名映射：跳过 0 层（规格书 §2.2） */
    @Test
    fun `floor label formatting skips zero floor`() {
        assertEquals("1F", FloorStateMachine.formatFloorLabel(0))
        assertEquals("2F", FloorStateMachine.formatFloorLabel(1))
        assertEquals("6F", FloorStateMachine.formatFloorLabel(5))
        assertEquals("B1", FloorStateMachine.formatFloorLabel(-1))
        assertEquals("B2", FloorStateMachine.formatFloorLabel(-2))
        assertEquals("B3", FloorStateMachine.formatFloorLabel(-3))
    }

    /** 卡尔曼滤波：收敛到输入、抑制单点毛刺、稳态增益符合理论值 */
    @Test
    fun `baro kalman filter converges and suppresses spikes`() {
        val filter = BaroKalmanFilter()

        var output = 0f
        repeat(1000) {
            output = filter.process(1013.25f)
        }
        assertEquals(1013.25f, output, 0.01f)
        assertEquals(filter.steadyStateGain(), filter.lastGain, 0.02)

        // 单点 +0.5 hPa 毛刺应被强烈抑制
        val spiked = filter.process(1013.75f)
        assertTrue("毛刺后的输出偏离必须 < 0.12 hPa", kotlin.math.abs(spiked - 1013.25f) < 0.12f)
        val recovered = filter.process(1013.25f)
        assertTrue(kotlin.math.abs(recovered - 1013.25f) < 0.12f)
    }

    /** 模拟器场景：电梯 1F -> 3F 的合成波形（无噪声）气压落差与脉冲齐全 */
    @Test
    fun `mock scenario produces elevator waveform`() = runTest {
        val source = MockSensorSource(addNoise = false, externalScope = backgroundScope)
        val frames = mutableListOf<SensorFrame>()
        val collector = launch {
            source.observeSensorFrames().take(40).toList(frames)
        }

        source.playScenario(
            steps = listOf(
                MockSensorSource.MockStep.Hold(durationMs = 1000),
                MockSensorSource.MockStep.ElevatorRide(
                    durationMs = 2000,
                    fromFloorIndex = 0,
                    toFloorIndex = 2,
                    profile = BuildingProfile.OFFICE
                ),
                MockSensorSource.MockStep.Hold(durationMs = 1000)
            ),
            startGroundPressureHpa = 1013.25f
        )
        collector.join()

        assertEquals(40, frames.size)
        assertTrue("时间戳必须单调递增", frames.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs })

        // 2 层楼 = 8.0m ≈ 0.949 hPa 落差
        assertEquals(1013.25f - 8.0f / 8.43f, frames.last().pressureHpa, 0.05f)

        // 电梯脉冲（|a_z| > 0.5 m/s²）必须存在
        assertTrue("电梯波形必须包含超重/失重脉冲", frames.any { kotlin.math.abs(it.linearAccZ) > 0.5f })
    }
}
