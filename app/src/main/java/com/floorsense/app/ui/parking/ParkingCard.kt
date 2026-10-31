package com.floorsense.app.ui.parking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floorsense.app.data.model.ParkingRecord
import com.floorsense.app.domain.engine.ParkingMemoryState
import com.floorsense.app.domain.engine.ParkingTrackPhase
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 停车楼层卡片与计时器（规格书 §ui/parking/ParkingCard）。
 *
 * 展示当前停车追踪阶段、预估/确认后的车位楼层与深度、下车后经过时间，
 * 以及 Room 中的最近停车记录。
 */
@Composable
fun ParkingCard(
    state: ParkingMemoryState,
    recentRecords: List<ParkingRecord>,
    onManualParked: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("停车记忆", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(phaseLabel(state.phase)) }
                )
            }

            if (state.parkedTimestampMs == null) {
                Text(
                    "尚未记录车位。车载蓝牙断开时自动开始，或点击下方按钮手动记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        state.estimatedFloorLabel ?: "--",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "深度 %.1f m".format(state.depthMeters),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "已停车 %s".format(formatElapsed(elapsedSecondsSince(state))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    if (state.isConfirmedByReturnAscent) {
                        "✔ 已通过回 1F 电梯行程精确确认"
                    } else {
                        "· 预估值：乘电梯回到 1F 后自动精确结算"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isConfirmedByReturnAscent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onManualParked,
                    enabled = state.phase == ParkingTrackPhase.IDLE_IN_VEHICLE
                ) {
                    Text("手动记录车位")
                }
                OutlinedButton(onClick = onReset) {
                    Text("重置")
                }
            }

            if (recentRecords.isNotEmpty()) {
                HorizontalDivider()
                Text("最近记录", style = MaterialTheme.typography.labelLarge)
                recentRecords.take(3).forEach { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.floorLabel,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "深度 %.1f m · %s%s".format(
                                record.depthMeters,
                                dateFormat.format(Date(record.parkedAtMs)),
                                if (record.confirmedByReturnAscent) " · 已确认" else " · 预估"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 下车后经过的秒数；结算后计时冻结 */
@Composable
private fun elapsedSecondsSince(state: ParkingMemoryState): Long {
    val parkedAt = state.parkedTimestampMs ?: return 0L
    val settled = state.phase == ParkingTrackPhase.SETTLED
    return produceState(initialValue = 0L, key1 = parkedAt, key2 = settled) {
        if (settled) return@produceState
        while (true) {
            value = (System.currentTimeMillis() - parkedAt) / 1000L
            delay(1_000)
        }
    }.value
}

private fun formatElapsed(seconds: Long): String {
    val min = seconds / 60
    val sec = seconds % 60
    return "%d:%02d".format(min, sec)
}

private fun phaseLabel(phase: ParkingTrackPhase): String = when (phase) {
    ParkingTrackPhase.IDLE_IN_VEHICLE -> "驾驶中"
    ParkingTrackPhase.PARKED_WAITING_ASCENT -> "已记车位"
    ParkingTrackPhase.ASCENDING_TO_GROUND -> "正在上楼"
    ParkingTrackPhase.SETTLED -> "已结算"
}

private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
