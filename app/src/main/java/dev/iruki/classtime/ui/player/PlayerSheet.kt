package dev.iruki.classtime.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.recordings.SPEEDS
import dev.iruki.classtime.ui.recordings.recordingTitle
import dev.iruki.classtime.ui.recordings.speedLabel
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.TimeUtils
import java.time.Instant
import java.time.ZoneId

/**
 * 재생 화면. 구글 녹음기(M3 Expressive)처럼 세 버튼의 높이를 80으로 맞추고, 위계는 너비와 색으로
 * 준다. 재생 버튼은 재생 중엔 모서리가 각지고(28) 멈추면 둥글어진다. 속도는 아래 줄 왼쪽.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    recording: Recording,
    icon: String,
    state: PlaybackState,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = dragging ?: state.positionMs.toFloat()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_cd_collapse))
                }
                Text(
                    recording.subject,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_menu_folder)) },
                            onClick = { menuOpen = false; onOpenFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                // 시트는 스크롤하지 않으므로, 낮은 화면에서는 위쪽 면만 줄여 조작부가 늘 보이게 한다.
                val heroHeight = (LocalConfiguration.current.screenHeightDp - 520).coerceIn(120, 300).dp
                Surface(
                    color = c.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().height(heroHeight),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(color = c.surface, shape = RoundedCornerShape(36.dp), modifier = Modifier.size(120.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    CourseIcons.of(icon, recording.subject).vector,
                                    contentDescription = null,
                                    tint = c.onSurfaceVariant,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                        }
                    }
                }

                Text(
                    recordingTitle(recording.startedAt),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 28.dp),
                )
                Text(
                    stringResource(
                        if (recording.auto) R.string.player_meta_auto else R.string.player_meta_manual,
                        clock(recording.startedAt),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Slider(
                    value = position.coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.toInt()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.padding(top = 24.dp).semantics {
                        stateDescription = TimeUtils.formatDuration(position.toLong()) + " / " +
                            TimeUtils.formatDuration(state.durationMs.toLong())
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(TimeUtils.formatDuration(position.toLong()), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(TimeUtils.formatDuration(state.durationMs.toLong()), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    GroupButton(Icons.Rounded.Replay10, stringResource(R.string.player_cd_back10), 72.dp, RoundedCornerShape(40.dp), c.secondaryContainer, c.onSecondaryContainer) {
                        onSeekBy(-10_000)
                    }
                    val corner by animateDpAsState(if (state.playing) 28.dp else 40.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "play-corner")
                    GroupButton(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playing) R.string.recordings_cd_pause else R.string.recordings_cd_play),
                        144.dp, RoundedCornerShape(corner), c.primary, c.onPrimary, iconSize = 36.dp, onClick = onToggle,
                    )
                    GroupButton(Icons.Rounded.Forward10, stringResource(R.string.player_cd_forward10), 72.dp, RoundedCornerShape(40.dp), c.secondaryContainer, c.onSecondaryContainer) {
                        onSeekBy(10_000)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.offset(x = (-12).dp)) {
                        val speedCd = stringResource(R.string.player_cd_speed, speedLabel(state.speed))
                        TextButton(onClick = { speedOpen = true }, modifier = Modifier.semantics { contentDescription = speedCd }) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(speedLabel(state.speed))
                        }
                        DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(s)) },
                                    leadingIcon = if (s == state.speed) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else null,
                                    onClick = { speedOpen = false; onSpeed(s) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onShare, modifier = Modifier.offset(x = 12.dp)) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.recordings_menu_share))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body, recording.fileName)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 재생 버튼 그룹의 한 칸. 셋 다 높이 80. */
@Composable
private fun GroupButton(
    icon: ImageVector,
    label: String,
    width: Dp,
    shape: RoundedCornerShape,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    iconSize: Dp = 32.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier.width(width).height(80.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

private fun clock(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return TimeUtils.minuteToText(t.hour * 60 + t.minute)
}
