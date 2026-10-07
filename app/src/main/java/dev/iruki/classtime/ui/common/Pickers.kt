package dev.iruki.classtime.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 24시간 분(minute-of-day)을 오전/오후 · 12시간제로. 순수 계산이라 테스트에서 그대로 쓴다. */
internal data class Clock12(val pm: Boolean, val hour: Int, val minute: Int) {
    fun toMinuteOfDay(): Int = ((hour % 12) + if (pm) 12 else 0) * 60 + minute

    companion object {
        fun of(minuteOfDay: Int): Clock12 {
            val h = (minuteOfDay / 60) % 24
            val h12 = (h % 12).let { if (it == 0) 12 else it }
            return Clock12(pm = h >= 12, hour = h12, minute = minuteOfDay % 60)
        }
    }
}

/**
 * 시각 고르기. 시계 다이얼 대신 **오전/오후 → 시 → 분** 칸으로 고른다.
 *
 * 다이얼은 (1) 24시간 다이얼의 안쪽·바깥쪽 고리가 무엇인지 알기 어려웠고, (2) 화면 높이가
 * 줄면 원이 찌그러지거나 잘렸다. 칸은 줄 바꿈만 될 뿐 깨지지 않고, 그래도 높이가 모자라면
 * 대화상자 안이 스크롤된다. 5분 단위가 아닌 시각은 키보드로 입력한다.
 */
@Composable
fun TimePickerDialog(
    initialMinute: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = remember(initialMinute) { Clock12.of(initialMinute) }
    var pm by remember { mutableStateOf(initial.pm) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var typing by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { typing = !typing }) {
                    Icon(
                        if (typing) Icons.Rounded.GridView else Icons.Rounded.Keyboard,
                        contentDescription = stringResource(if (typing) R.string.time_pick_grid else R.string.time_pick_keyboard),
                        tint = c.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(if (pm) R.string.time_pm else R.string.time_am),
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text("%d:%02d".format(hour, minute), style = MaterialTheme.typography.displayMedium)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    listOf(false, true).forEachIndexed { i, isPm ->
                        SegmentedButton(
                            selected = pm == isPm,
                            onClick = { pm = isPm },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(stringResource(if (isPm) R.string.time_pm else R.string.time_am)) }
                    }
                }
                if (typing) {
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(stringResource(R.string.time_hour), hour, 1..12, Modifier.weight(1f)) { hour = it }
                        NumberField(stringResource(R.string.time_minute), minute, 0..59, Modifier.weight(1f)) { minute = it }
                    }
                } else {
                    GridLabel(stringResource(R.string.time_hour))
                    CellGrid(HOURS, selected = hour, label = { it.toString() }, aria = { stringResource(R.string.time_hour_cd, it) }) { hour = it }
                    GridLabel(stringResource(R.string.time_minute))
                    CellGrid(MINUTES, selected = minute, label = { "%02d".format(it) }, aria = { stringResource(R.string.time_minute_cd, it) }) { minute = it }
                    if (minute % 5 != 0) {
                        Text(
                            stringResource(R.string.time_pick_odd_minute),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(Clock12(pm, hour, minute).toMinuteOfDay()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val HOURS = listOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
private val MINUTES = (0..55 step 5).toList()

@Composable
private fun GridLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** 6칸씩 줄 바꿈하는 선택 칸. 화면이 좁거나 낮아도 원처럼 찌그러지지 않는다. */
@Composable
private fun CellGrid(
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    aria: @Composable (Int) -> String,
    onPick: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { v ->
                    val on = v == selected
                    val description = aria(v)
                    Surface(
                        onClick = { onPick(v) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (on) c.primary else c.surfaceContainerLowest,
                        contentColor = if (on) c.onPrimary else c.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .semantics {
                                role = Role.RadioButton
                                selected = on
                                contentDescription = description
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label(v), style = if (on) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(2)
            text = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerModal(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
