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
