package dev.iruki.classtime.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.iruki.classtime.R

/** 분 단위 길이를 “1시간 3분” / “33분” 처럼. 음수는 0분으로. */
@Composable
fun formatSpan(minutes: Int): String {
    val m = minutes.coerceAtLeast(0)
    val h = m / 60
    val r = m % 60
    return when {
        h > 0 && r > 0 -> stringResource(R.string.duration_hours_minutes, h, r)
        h > 0 -> stringResource(R.string.duration_hours, h)
        else -> stringResource(R.string.duration_minutes, r)
    }
}

/** 분(minute-of-day)을 “오전 9:00” / “오후 1:30” 처럼. 시간을 고르는 자리에서 쓴다. */
@Composable
fun clockLabel(minuteOfDay: Int): String {
    val c = Clock12.of(minuteOfDay)
    return stringResource(if (c.pm) R.string.time_pm else R.string.time_am) + " " + "%d:%02d".format(c.hour, c.minute)
}
