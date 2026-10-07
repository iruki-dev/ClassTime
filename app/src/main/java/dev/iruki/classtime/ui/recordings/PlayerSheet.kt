package dev.iruki.classtime.ui.recordings

import dev.iruki.classtime.audio.PlaybackState

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.iruki.classtime.R
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.theme.CourseColor
import dev.iruki.classtime.ui.theme.tones
import dev.iruki.classtime.util.TimeUtils

/** 재생 화면. 복습에 필요한 것만: 위치, 10초 앞/뒤, 배속, 보내기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    recording: Recording,
    color: CourseColor,
    state: PlaybackState,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onShare: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val (art, onArt) = color.tones()
    // 끄는 동안에는 손가락 위치를, 놓으면 실제 재생 위치를 보여 준다.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = dragging ?: state.positionMs.toFloat()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.4f).background(art, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(recording.subject.take(1), color = onArt, fontSize = 96.sp, style = MaterialTheme.typography.displayLarge)
            }

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${recordingTitle(recording.startedAt)} · ${recording.subject}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(
                            if (recording.auto) R.string.player_meta_auto else R.string.player_meta_manual,
                            stringResource(R.string.player_folder, recording.subject),
                            TimeUtils.formatSize(recording.sizeBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.recordings_menu_share))
                }
            }

            Slider(
                value = position.coerceIn(0f, duration.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toInt()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.padding(top = 16.dp).semantics {
                    stateDescription = TimeUtils.formatDuration(position.toLong()) + " / " +
                        TimeUtils.formatDuration(state.durationMs.toLong())
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(TimeUtils.formatDuration(position.toLong()), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                Box(Modifier.weight(1f))
                Text(
                    "−" + TimeUtils.formatDuration((state.durationMs - position.toLong()).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.player_cd_back10), modifier = Modifier.size(32.dp))
                }
                // M3 Expressive 의 큰 재생 버튼: 재생 중엔 둥근 사각, 멈추면 더 둥글게.
                Surface(
                    onClick = onToggle,
                    color = c.primary,
                    contentColor = c.onPrimary,
                    shape = RoundedCornerShape(if (state.playing) 28.dp else 48.dp),
                    modifier = Modifier.width(136.dp).height(96.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.playing) R.string.recordings_cd_pause else R.string.recordings_cd_play),
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                FilledTonalIconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(72.dp)) {
                    Icon(Icons.Rounded.Forward10, contentDescription = stringResource(R.string.player_cd_forward10), modifier = Modifier.size(32.dp))
                }
            }

            Text(
                stringResource(R.string.player_speed),
                style = MaterialTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SPEEDS.forEachIndexed { i, speed ->
                    SegmentedButton(
                        selected = state.speed == speed,
                        onClick = { onSpeed(speed) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = SPEEDS.size),
                    ) { Text(speedLabel(speed)) }
                }
            }
        }
    }
}
