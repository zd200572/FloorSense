package com.floorsense.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.data.sensor.MockSensorSource
import com.floorsense.app.domain.filter.MotionMode
import com.floorsense.app.ui.parking.ParkingCard

/**
 * 主控面板（规格书 §ui/dashboard/DashboardScreen）：
 * 实时气压波形、当前楼层大字、运动门控状态、模拟器注入控制台、停车卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val state by viewModel.uiState.collectAsState()
    val recentRecords by viewModel.recentRecords.collectAsState()
    var showCalibration by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("FloorSense 楼层感知") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FloorHeroCard(state.floor)

            PressureChart(points = state.chart)

            ParkingCard(
                state = state.parking,
                recentRecords = recentRecords,
                onManualParked = viewModel::manualParked,
                onReset = viewModel::resetParking
            )

            SourceAndProfileRow(state, viewModel, onCalibrateClick = { showCalibration = true })

            if (state.mode == SensorMode.MOCK) {
                SimulatorConsole(state, viewModel)
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showCalibration) {
        CalibrationDialog(
            currentFloorIndex = state.floor.currentFloorIndex,
            currentProfile = state.profile,
            onDismiss = { showCalibration = false },
            onConfirm = { floorIndex, standardM, b1M, subM ->
                showCalibration = false
                viewModel.calibrateWithHeights(floorIndex, standardM, b1M, subM)
            }
        )
    }
}

@Composable
private fun FloorHeroCard(floor: com.floorsense.app.domain.engine.FloorUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("当前楼层", style = MaterialTheme.typography.labelMedium)
                Text(
                    floor.currentFloorLabel,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "相对 1F 基准面 %+.1f m".format(floor.relativeAltitudeM),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "基准 %.2f hPa · 当前 %.2f hPa".format(floor.basePressureHpa, floor.currentPressureHpa),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MotionBadge(floor.motionMode)
                Spacer(Modifier.height(6.dp))
                Text("Δh = -8.43·ΔP", style = MaterialTheme.typography.labelSmall)
                Text("运动门控防漂移", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MotionBadge(mode: MotionMode) {
    val (label, container, content) = when (mode) {
        MotionMode.STATIONARY -> Triple("静止", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        MotionMode.ELEVATOR -> Triple("电梯", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        MotionMode.STAIRS_OR_RAMP -> Triple("楼梯/坡道", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = content
        )
    )
}

@Composable
private fun PressureChart(points: List<ChartPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("实时气压波形（60s）", style = MaterialTheme.typography.titleSmall)

            if (points.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "等待传感器数据…（点击下方场景按钮开始模拟）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val primaryColor = MaterialTheme.colorScheme.primary
                val outlineColor = MaterialTheme.colorScheme.outline
                val tertiaryColor = MaterialTheme.colorScheme.tertiary

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    val rawMin = points.minOf { it.rawHpa }
                    val rawMax = points.maxOf { it.rawHpa }
                    val base = points.last().baseHpa
                    val minY = minOf(rawMin, base) - 0.3f
                    val maxY = maxOf(rawMax, base) + 0.3f
                    val spanY = (maxY - minY).coerceAtLeast(0.1f)

                    fun yOf(v: Float) = size.height * (1f - (v - minY) / spanY)
                    fun xOf(i: Int) = size.width * i / (points.size - 1)

                    // 动态基准线 P_base（虚线）
                    drawLine(
                        color = tertiaryColor,
                        start = Offset(0f, yOf(base)),
                        end = Offset(size.width, yOf(base)),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )

                    // 卡尔曼显示平滑曲线
                    val smoothPath = Path()
                    points.forEachIndexed { i, p ->
                        val offset = Offset(xOf(i), yOf(p.displayHpa))
                        if (i == 0) smoothPath.moveTo(offset.x, offset.y) else smoothPath.lineTo(offset.x, offset.y)
                    }
                    drawPath(smoothPath, color = primaryColor, style = Stroke(width = 4f))

                    // 原始气压毛刺
                    val rawPath = Path()
                    points.forEachIndexed { i, p ->
                        val offset = Offset(xOf(i), yOf(p.rawHpa))
                        if (i == 0) rawPath.moveTo(offset.x, offset.y) else rawPath.lineTo(offset.x, offset.y)
                    }
                    drawPath(rawPath, color = outlineColor.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendDot(outlineColor, "原始")
                    LegendDot(MaterialTheme.colorScheme.primary, "滤波")
                    LegendDot(MaterialTheme.colorScheme.tertiary, "P_base 基准")
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SourceAndProfileRow(
    state: DashboardUiState,
    viewModel: DashboardViewModel,
    onCalibrateClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == SensorMode.MOCK,
                onClick = { viewModel.switchSource(SensorMode.MOCK) },
                label = { Text("模拟器数据") }
            )
            FilterChip(
                selected = state.mode == SensorMode.HARDWARE,
                onClick = { viewModel.switchSource(SensorMode.HARDWARE) },
                label = { Text("真实传感器") }
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCalibrateClick) {
                Text("校准楼层")
            }
        }
        // 模板选项：三个预设 + 当前正在使用的自定义模板（如有）
        val profileOptions = if (BuildingProfile.ALL.any { it.name == state.profile.name }) {
            BuildingProfile.ALL
        } else {
            BuildingProfile.ALL + state.profile
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            profileOptions.forEach { profile ->
                FilterChip(
                    selected = state.profile == profile,
                    onClick = { viewModel.selectProfile(profile) },
                    label = { Text(profile.name) }
                )
            }
        }
    }
}

/** 模拟器注入控制台：一键场景 + 滑杆手动注入气压 */
@Composable
private fun SimulatorConsole(state: DashboardUiState, viewModel: DashboardViewModel) {
    var sliderPressure by remember { mutableFloatStateOf(MockSensorSource.DEFAULT_GROUND_PRESSURE) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("模拟器注入控制台", style = MaterialTheme.typography.titleMedium)

            ScenarioKind.entries.chunked(2).forEach { rowKinds ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKinds.forEach { kind ->
                        Button(
                            onClick = { viewModel.playScenario(kind) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.scenarioPlaying
                        ) {
                            Text(kind.label, maxLines = 1)
                        }
                    }
                }
            }

            if (state.scenarioPlaying) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = viewModel::stopScenario) {
                    Text("停止场景")
                }
            }

            HorizontalDivider()

            Text(
                "手动注入气压：%.2f hPa".format(sliderPressure),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = sliderPressure,
                onValueChange = { sliderPressure = it },
                onValueChangeFinished = { viewModel.injectPressure(sliderPressure) },
                valueRange = 990f..1035f
            )
            Text(
                "提示：静止时向下拖动气压不会跳层（基准锚点会吸收漂移）；电梯场景请用上方按钮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
