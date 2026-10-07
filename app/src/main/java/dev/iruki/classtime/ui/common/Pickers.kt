package dev.iruki.classtime.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 시각 고르기: M3 시계 다이얼 + 오전/오후.
 *
 * material3 1.3 의 다이얼은 늘 256dp 이고, 기본 배치(세로/가로)를 **화면** 비율로 정한다.
 * 그래서 분할 화면이나 낮은 창에서는 다이얼이 잘리거나 대화상자가 찌그러졌다.
 * 여기서는 **창 높이**로 직접 고른다 — 넉넉하면 세로, 낮으면 가로, 가로도 안 들어가면
 * 키보드 입력. 다이얼은 어느 경우에도 정원 그대로다(M3 스펙: 스크롤하지 않는다).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialMinute: Int,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = (initialMinute / 60) % 24,
        initialMinute = initialMinute % 60,
        is24Hour = false,
    )
    val layout = TimePickerFit.of(LocalConfiguration.current.screenHeightDp)
    var typing by remember { mutableStateOf(layout == TimePickerFit.INPUT) }
    val c = MaterialTheme.colorScheme
    val colors = TimePickerDefaults.colors(
        // 오전/오후 선택은 앱의 다른 선택(칩·연결 버튼)과 같은 secondaryContainer 로 맞춘다.
        periodSelectorSelectedContainerColor = c.secondaryContainer,
        periodSelectorSelectedContentColor = c.onSecondaryContainer,
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = c.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 640.dp),
        ) {
            // 가로 배치는 낮은 창(가로 휴대폰 ≈ 390dp)에 들어가야 하므로 여백을 줄인다.
            val compact = layout != TimePickerFit.VERTICAL
            Column(Modifier.padding(if (compact) 16.dp else 24.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (compact) 8.dp else 20.dp),
                )
                Box(Modifier.align(Alignment.CenterHorizontally)) {
                    if (typing) {
                        TimeInput(state = state, colors = colors)
                    } else {
                        TimePicker(
                            state = state,
                            colors = colors,
                            layoutType = if (layout == TimePickerFit.VERTICAL) TimePickerLayoutType.Vertical
                            else TimePickerLayoutType.Horizontal,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 창이 너무 낮으면 다이얼로 돌아갈 수 없다(들어가지 않으므로).
                    if (layout != TimePickerFit.INPUT) {
                        IconButton(onClick = { typing = !typing }, modifier = Modifier.offset(x = (-12).dp)) {
                            Icon(
                                if (typing) Icons.Rounded.Schedule else Icons.Rounded.Keyboard,
                                contentDescription = stringResource(if (typing) R.string.time_pick_dial else R.string.time_pick_keyboard),
                                tint = c.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text(stringResource(R.string.action_confirm))
                    }
                }
            }
        }
    }
}

/** 창 높이(dp)에 들어가는 시각 고르기 배치. 경계는 대화상자 전체 높이(여백·버튼 포함)로 잡았다. */
internal enum class TimePickerFit {
    VERTICAL, HORIZONTAL, INPUT;

    companion object {
        fun of(windowHeightDp: Int): TimePickerFit = when {
            windowHeightDp >= 600 -> VERTICAL
            windowHeightDp >= 380 -> HORIZONTAL
            else -> INPUT
        }
    }
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
