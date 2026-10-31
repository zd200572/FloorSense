package com.floorsense.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floorsense.app.data.model.BuildingProfile
import com.floorsense.app.domain.engine.FloorStateMachine

/**
 * 手动校准对话框：
 * 「我现在在 N 楼」以当前气压重锚定楼层基准（支持地上/地下，跳过 0 层命名），
 * 「层高我也知道」可同时填入自定义层高（米）并保存为「自定义」模板，留默认则沿用当前模板。
 */
@Composable
fun CalibrationDialog(
    currentFloorIndex: Int,
    currentProfile: BuildingProfile,
    onDismiss: () -> Unit,
    onConfirm: (floorIndex: Int, standardM: Float, b1M: Float, subM: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var floorIndex by remember { mutableIntStateOf(currentFloorIndex) }
    var standardText by remember { mutableStateOf(currentProfile.standardFloorHeightM.toString()) }
    var b1Text by remember { mutableStateOf(currentProfile.basementFirstHeightM.toString()) }
    var subText by remember { mutableStateOf(currentProfile.basementSubHeightM.toString()) }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("手动校准") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("我当前所在楼层", style = MaterialTheme.typography.labelLarge)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (floorIndex > MIN_FLOOR_INDEX) floorIndex-- }
                    ) { Text("−", fontSize = 20.sp) }

                    Text(
                        FloorStateMachine.formatFloorLabel(floorIndex),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = { if (floorIndex < MAX_FLOOR_INDEX) floorIndex++ }
                    ) { Text("+", fontSize = 20.sp) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, -1, -2, -3).forEach { idx ->
                        FilterChip(
                            selected = floorIndex == idx,
                            onClick = { floorIndex = idx },
                            label = { Text(FloorStateMachine.formatFloorLabel(idx)) }
                        )
                    }
                }

                HorizontalDivider()

                Text("层高（米，不确定就保持默认）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = standardText,
                    onValueChange = { standardText = it },
                    label = { Text("地上标准层高") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = b1Text,
                        onValueChange = { b1Text = it },
                        label = { Text("1F→B1 落差") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = subText,
                        onValueChange = { subText = it },
                        label = { Text("地下层高") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    "校准会以当前气压重新锚定基准；层高与默认值不同时将保存为「自定义」模板。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val standard = (standardText.toFloatOrNull() ?: currentProfile.standardFloorHeightM)
                    .coerceIn(2f, 8f)
                val b1 = (b1Text.toFloatOrNull() ?: currentProfile.basementFirstHeightM)
                    .coerceIn(2.5f, 7f)
                val sub = (subText.toFloatOrNull() ?: currentProfile.basementSubHeightM)
                    .coerceIn(2f, 6f)
                onConfirm(floorIndex, standard, b1, sub)
            }) { Text("校准") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private const val MIN_FLOOR_INDEX = -5
private const val MAX_FLOOR_INDEX = 30
