package com.floorsense.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.floorsense.app.data.db.FloorSenseDatabase
import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.model.ParkingRecord
import com.floorsense.app.data.model.SensorFrame
import com.floorsense.app.data.sensor.HardwareSensorSource
import com.floorsense.app.data.sensor.MockSensorSource
import com.floorsense.app.data.sensor.SensorDataSource
import com.floorsense.app.data.settings.AnchorSnapshot
import com.floorsense.app.data.settings.UserPreferencesRepository
import com.floorsense.app.domain.engine.FloorStateMachine
import com.floorsense.app.domain.engine.FloorUiState
import com.floorsense.app.domain.engine.ParkingMemoryEngine
import com.floorsense.app.domain.engine.ParkingMemoryState
import com.floorsense.app.domain.engine.ParkingTrackPhase
import com.floorsense.app.domain.filter.BaroKalmanFilter
import com.floorsense.app.domain.filter.FilteredSensorState
import com.floorsense.app.domain.filter.VerticalMotionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 数据源模式：合成波形模拟器 / 真实硬件传感器 */
enum class SensorMode { MOCK, HARDWARE }

/** 模拟器控制台的一键场景 */
enum class ScenarioKind(val label: String) {
    TYPHOON("台风过境"),
    ELEVATOR_ROUND("电梯往返"),
    PARKING_ROUND_TRIP("进B3+回1F"),
    STAIRS("爬楼梯"),
}

/** 波形图数据点：原始气压 / 卡尔曼显示平滑 / 动态基准线 */
data class ChartPoint(
    val timestampMs: Long,
    val rawHpa: Float,
    val displayHpa: Float,
    val baseHpa: Float
)

data class DashboardUiState(
    val floor: FloorUiState = FloorUiState(),
    val parking: ParkingMemoryState = ParkingMemoryState(),
    val chart: List<ChartPoint> = emptyList(),
    val mode: SensorMode = SensorMode.HARDWARE,
    val profile: BuildingProfile = BuildingProfile.OFFICE,
    val scenarioPlaying: Boolean = false,
    val hardwareError: String? = null
)

/**
 * Dashboard 状态中枢：把传感器流接入门控 → 楼层状态机 / 停车引擎，
 * 并对外暴露模拟器注入控制台与建筑模板切换。
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val mockSource = MockSensorSource()
    private val hardwareSource by lazy { HardwareSensorSource(application) }
    private val gate = VerticalMotionGate()
    private val floorEngine = FloorStateMachine(BuildingProfile.OFFICE)
    private val parkingEngine = ParkingMemoryEngine(BuildingProfile.OFFICE)
    private val kalman = BaroKalmanFilter()
    private val prefs = UserPreferencesRepository(application)
    private val dao = FloorSenseDatabase.get(application).parkingRecordDao()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val recentRecords: StateFlow<List<ParkingRecord>> = dao.observeRecent(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var collectJob: Job? = null
    private var lastFiltered: FilteredSensorState? = null
    private var framesProcessed = 0
    private var lastAnchorSaveMs = 0L
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            prefs.buildingProfile.collect { profile ->
                floorEngine.updateProfile(profile)
                parkingEngine.updateProfile(profile)
                _uiState.update { it.copy(profile = profile) }
            }
        }
        restorePersistedAnchor()
        startPipeline(SensorMode.HARDWARE)
    }

    fun switchSource(mode: SensorMode) {
        if (mode == _uiState.value.mode) return
        // 手动切换数据源意味着物理场景变更，清除持久化锚点避免恢复过期状态
        startPipeline(mode, clearPersistedAnchor = true)
    }

    private fun startPipeline(mode: SensorMode, clearPersistedAnchor: Boolean = false) {
        collectJob?.cancel()
        stopScenario()
        // 切换数据源意味着物理场景变更，全部状态复位重锚定
        gate.reset()
        floorEngine.reset()
        parkingEngine.reset()
        kalman.reset()
        framesProcessed = 0
        _uiState.update { it.copy(mode = mode, chart = emptyList(), hardwareError = null) }
        if (clearPersistedAnchor) {
            persistenceScope.launch { prefs.clearAnchor() }
        }

        val source: SensorDataSource =
            if (mode == SensorMode.MOCK) mockSource else hardwareSource
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                source.observeSensorFrames().collect { frame -> onFrame(frame) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                // 设备没有气压计等硬件故障：给出提示并降级回模拟器模式
                if (mode == SensorMode.HARDWARE) {
                    _uiState.update { it.copy(hardwareError = error.message ?: "真实传感器不可用") }
                    startPipeline(SensorMode.MOCK)
                }
            }
        }
    }

    private fun onFrame(frame: SensorFrame) {
        val filtered = gate.process(frame)
        lastFiltered = filtered
        floorEngine.update(filtered)
        parkingEngine.onSensorFrame(filtered, hasGps = frame.hasGpsSignal)
        val displayP = kalman.process(frame.pressureHpa)
        framesProcessed++

        _uiState.update { st ->
            val point = ChartPoint(
                timestampMs = frame.timestampMs,
                rawHpa = frame.pressureHpa,
                displayHpa = displayP,
                baseHpa = st.floor.basePressureHpa
            )
            st.copy(
                floor = floorEngine.state.value,
                parking = parkingEngine.parkingState.value,
                chart = (st.chart + point).takeLast(CHART_CAPACITY)
            )
        }
        maybePersistAnchor()
    }

    // ---------- 模拟器控制台 ----------

    fun playScenario(kind: ScenarioKind) {
        val (steps, totalMs) = when (kind) {
            ScenarioKind.TYPHOON -> scenario(
                MockSensorSource.MockStep.Hold(durationMs = 600_000, totalPressureDriftHpa = -2.5f)
            )
            ScenarioKind.ELEVATOR_ROUND -> scenario(
                MockSensorSource.MockStep.Hold(3_000),
                MockSensorSource.MockStep.ElevatorRide(8_000, fromFloorIndex = 0, toFloorIndex = 5),
                MockSensorSource.MockStep.Hold(5_000),
                MockSensorSource.MockStep.ElevatorRide(8_000, fromFloorIndex = 5, toFloorIndex = 0),
                MockSensorSource.MockStep.Hold(3_000)
            )
            ScenarioKind.PARKING_ROUND_TRIP -> scenario(
                MockSensorSource.MockStep.Hold(8_000, gps = true),
                MockSensorSource.MockStep.VehicleRamp(20_000, depthMeters = 11.3f, downward = true),
                MockSensorSource.MockStep.Hold(10_000),
                MockSensorSource.MockStep.ElevatorRide(8_000, fromFloorIndex = -3, toFloorIndex = 0),
                MockSensorSource.MockStep.Hold(8_000, gps = true)
            )
            ScenarioKind.STAIRS -> scenario(
                MockSensorSource.MockStep.Hold(2_000),
                MockSensorSource.MockStep.StairClimb(15_000, altitudeMeters = 8.0f, upward = true),
                MockSensorSource.MockStep.Hold(5_000)
            )
        }
        mockSource.playScenario(steps)
        _uiState.update { it.copy(scenarioPlaying = true) }
        viewModelScope.launch {
            delay(totalMs)
            _uiState.update { it.copy(scenarioPlaying = false) }
        }
    }

    fun stopScenario() {
        mockSource.stopScenario()
        _uiState.update { it.copy(scenarioPlaying = false) }
    }

    /** 手动注入一帧气压（滑杆拖动） */
    fun injectPressure(hpa: Float) {
        if (_uiState.value.mode == SensorMode.MOCK) {
            mockSource.emitManual(hpa)
        }
    }

    // ---------- 停车记忆 ----------

    /** 模拟车载蓝牙断开：以最近一帧滤波气压锁定车位锚点 */
    fun manualParked() {
        val filtered = lastFiltered ?: return
        parkingEngine.onVehicleParked(filtered.timestampMs, filtered.smoothPressureHpa)
        syncEngineStateToUi()
        maybePersistAnchor(force = true)
    }

    fun resetParking() {
        parkingEngine.reset()
        syncEngineStateToUi()
        maybePersistAnchor(force = true)
    }

    /** 手动锚点校准：把当前时刻强制认定为指定显示楼层（0=1F，5=6F，-2=B2） */
    fun calibrateCurrentFloor(floorIndex: Int) {
        val filtered = lastFiltered ?: return
        floorEngine.calibrateCurrentFloor(floorIndex, filtered.smoothPressureHpa)
        syncEngineStateToUi()
        maybePersistAnchor(force = true)
    }

    /**
     * 「我现在在 N 楼，层高我也知道」式校准：
     * 同时保存自定义层高模板（地上层高/B1 落差/地下层高）并以当前气压锚定到指定楼层。
     * 层高必须先于锚点计算生效，因此在本协程内同步应用后再校准。
     */
    fun calibrateWithHeights(floorIndex: Int, standardM: Float, b1M: Float, subM: Float) {
        val filtered = lastFiltered ?: return
        viewModelScope.launch {
            val current = _uiState.value.profile
            val heightsChanged = standardM != current.standardFloorHeightM ||
                    b1M != current.basementFirstHeightM ||
                    subM != current.basementSubHeightM
            if (heightsChanged) {
                val custom = BuildingProfile("自定义", standardM, b1M, subM)
                prefs.setCustomProfile(standardM, b1M, subM)
                floorEngine.updateProfile(custom)
                parkingEngine.updateProfile(custom)
            }
            floorEngine.calibrateCurrentFloor(floorIndex, filtered.smoothPressureHpa)
            syncEngineStateToUi()
            maybePersistAnchor(force = true)
        }
    }

    // ---------- 设置 ----------

    fun selectProfile(profile: BuildingProfile) {
        viewModelScope.launch { prefs.setBuildingProfile(profile) }
    }

    // ---------- 退出记忆（锚点持久化） ----------

    private fun syncEngineStateToUi() {
        _uiState.update {
            it.copy(floor = floorEngine.state.value, parking = parkingEngine.parkingState.value)
        }
    }

    /** 应用启动时恢复上次退出保存的锚点与未结算停车状态（30 分钟内有效，仅真实传感器模式） */
    private fun restorePersistedAnchor() {
        viewModelScope.launch {
            val snap = prefs.loadAnchor() ?: return@launch
            val fresh = System.currentTimeMillis() - snap.savedAtMs <= ANCHOR_MAX_AGE_MS
            if (!fresh || snap.mode != SensorMode.HARDWARE.name) return@launch

            floorEngine.restoreAnchor(snap.basePressure1F, snap.lockedAltitudeM)
            if (snap.parkingPhase == ParkingTrackPhase.PARKED_WAITING_ASCENT.name &&
                snap.carParkedPressureHpa != null &&
                snap.parkedTimestampMs != null
            ) {
                parkingEngine.restoreParked(
                    carParkedPressureHpa = snap.carParkedPressureHpa,
                    parkedAtMs = snap.parkedTimestampMs,
                    estimatedFloorLabel = snap.estimatedFloorLabel,
                    depthMeters = snap.depthMeters ?: 0f,
                    confirmedByReturnAscent = snap.confirmedByReturnAscent
                )
            }
            syncEngineStateToUi()
        }
    }

    /**
     * 退出记忆机制：周期性持久化楼层锚点与未结算停车状态（节流 10 秒一次）。
     * 仅真实传感器模式下保存——模拟器锚点是虚构数据，恢复会污染真实会话。
     * 应用正常退出由 [onCleared] 立即保存兜底；系统杀进程由周期保存兜底。
     */
    private fun maybePersistAnchor(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAnchorSaveMs < ANCHOR_SAVE_INTERVAL_MS) return
        lastAnchorSaveMs = now

        val st = _uiState.value
        val floor = st.floor
        val parking = st.parking
        if (st.mode != SensorMode.HARDWARE || framesProcessed == 0 || floor.basePressureHpa.isNaN()) {
            return
        }

        val parkedWaiting = parking.phase == ParkingTrackPhase.PARKED_WAITING_ASCENT
        persistenceScope.launch {
            prefs.saveAnchor(
                AnchorSnapshot(
                    mode = SensorMode.HARDWARE.name,
                    basePressure1F = floor.basePressureHpa,
                    lockedAltitudeM = floor.relativeAltitudeM,
                    parkingPhase = if (parkedWaiting) parking.phase.name else null,
                    carParkedPressureHpa = if (parkedWaiting) parking.carParkedPressureHpa else null,
                    parkedTimestampMs = if (parkedWaiting) parking.parkedTimestampMs else null,
                    estimatedFloorLabel = if (parkedWaiting) parking.estimatedFloorLabel else null,
                    depthMeters = if (parkedWaiting) parking.depthMeters else null,
                    confirmedByReturnAscent = if (parkedWaiting) parking.isConfirmedByReturnAscent else false,
                    savedAtMs = now
                )
            )
        }
    }

    override fun onCleared() {
        maybePersistAnchor(force = true)
        super.onCleared()
    }

    private fun scenario(vararg steps: MockSensorSource.MockStep): Pair<List<MockSensorSource.MockStep>, Long> {
        val list = steps.toList()
        return list to list.sumOf { it.durationMs }
    }

    private companion object {
        const val CHART_CAPACITY = 600          // 最近 60 秒波形
        const val ANCHOR_SAVE_INTERVAL_MS = 10_000L
        const val ANCHOR_MAX_AGE_MS = 30 * 60_000L
    }
}
