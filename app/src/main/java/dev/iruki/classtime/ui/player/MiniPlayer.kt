package dev.iruki.classtime.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.recordings.recordingTitle
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.TimeUtils

/**
 * 하단 탭 위에 떠 있는 재생 막대. 모든 탭에서 보인다.
 * 좌우로 밀거나 ×를 누르면 재생을 멈추고 사라진다. 누르면 재생 화면이 열린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    recording: Recording,
    icon: String,
    state: PlaybackState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 다른 녹음으로 바뀌면 밀기 상태를 새로 시작한다.
    key(recording.id) {
        val dismiss = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) onClose()
                true
            },
            positionalThreshold = { width -> width * 0.4f },
        )
        SwipeToDismissBox(state = dismiss, backgroundContent = {}, modifier = modifier) {
            MiniPlayerBar(recording, icon, state, onToggle, onOpen, onClose)
        }
    }
}

@Composable
private fun MiniPlayerBar(
    recording: Recording,
    icon: String,
    state: PlaybackState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        color = c.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Box {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp).fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CourseIconTile(CourseIcons.of(icon, recording.subject), container = c.surfaceContainer)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        "${recording.subject} · ${recordingTitle(recording.startedAt)}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        TimeUtils.formatDuration(state.positionMs.toLong()) + " / " +
                            TimeUtils.formatDuration(state.durationMs.toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.recordings_cd_pause else R.string.recordings_cd_play),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.player_cd_close), tint = c.onSurfaceVariant)
                }
            }
            val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(c.primary)
            )
        }
    }
}
