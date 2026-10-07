package dev.iruki.classtime.ui.recordings

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.common.CourseAvatar
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.LargeHeader
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseColor
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.ui.theme.accent
import dev.iruki.classtime.util.TimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecordingsScreen() {
    val vm: RecordingsViewModel = hiltViewModel()
    val groups by vm.grouped.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val recordingActive by vm.recordingActive.collectAsStateWithLifecycle()
    val subjectColors by vm.subjectColors.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var showStorage by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }

    val colorOf = { subject: String -> CourseColors.of(subjectColors[subject] ?: CourseColors.palette.last().seed) }
    val all = groups.flatMap { it.second }
    val current = all.firstOrNull { it.id == playback.recordingId }
    val visible = if (filter == null) groups else groups.filter { it.first == filter }

    Scaffold(
        containerColor = AppTheme.colors.page,
        contentWindowInsets = WindowInsets.statusBars,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = if (current != null) 96.dp else 24.dp)) {
                item(key = "header") {
                    LargeHeader(
                        title = stringResource(R.string.recordings_title),
                        subtitle = if (all.isEmpty()) null else stringResource(
                            R.string.recordings_subtitle,
                            all.size,
                            TimeUtils.formatSize(all.sumOf { it.sizeBytes }),
                        ),
                    ) {
                        IconButton(onClick = { showStorage = true }) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.recordings_cd_storage))
                        }
                    }
                }

                if (all.isEmpty()) {
                    item(key = "empty") { EmptyRecordings() }
                    return@LazyColumn
                }

                if (groups.size > 1) {
                    item(key = "chips") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            item {
                                SubjectChip(stringResource(R.string.recordings_filter_all), filter == null, null) { vm.setFilter(null) }
                            }
                            items(groups, key = { it.first }) { (subject, _) ->
                                SubjectChip(subject, filter == subject, colorOf(subject)) {
                                    vm.setFilter(if (filter == subject) null else subject)
                                }
                            }
                        }
                    }
                }

                visible.forEach { (subject, recordings) ->
                    item(key = "header_$subject") {
                        GroupHeader(
                            subject = subject,
                            count = recordings.size,
                            totalBytes = recordings.sumOf { it.sizeBytes },
                            onShareAll = { vm.shareAll(recordings) },
                        )
                    }
                    itemsIndexed(recordings, key = { _, r -> r.id }) { i, recording ->
                        RecordingRow(
                            recording = recording,
                            index = i,
                            count = recordings.size,
                            liveNow = recording.ongoing && recordingActive,
                            isCurrent = playback.recordingId == recording.id,
                            playing = playback.recordingId == recording.id && playback.playing,
                            onToggle = { vm.toggle(recording) },
                            onOpenPlayer = {
                                if (playback.recordingId != recording.id) vm.toggle(recording)
                                playerOpen = true
                            },
                            onShare = { vm.share(recording) },
                            onRename = { renaming = recording },
                            onDelete = { deleting = recording },
                        )
                    }
                }
            }

            if (current != null) {
                MiniPlayer(
                    recording = current,
                    color = colorOf(current.subject),
                    state = playback,
                    onToggle = { vm.toggle(current) },
                    onOpen = { playerOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }
    }

    if (playerOpen && current != null) {
        PlayerSheet(
            recording = current,
            color = colorOf(current.subject),
            state = playback,
            onDismiss = { playerOpen = false },
            onToggle = { vm.toggle(current) },
            onSeek = vm::seekTo,
            onSeekBy = vm::seekBy,
            onSpeed = vm::setSpeed,
            onShare = { vm.share(current) },
        )
    }

    renaming?.let { rec ->
        var text by remember(rec.id) { mutableStateOf(rec.fileName.removeSuffix(".m4a")) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.recordings_rename_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.recordings_rename_field)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.rename(rec, text); renaming = null }) {
                    Text(stringResource(R.string.recordings_rename_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body, rec.fileName)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(rec); deleting = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showStorage) {
        AlertDialog(
            onDismissRequest = { showStorage = false },
            icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
            title = { Text(stringResource(R.string.recordings_storage_title)) },
            text = { Text(stringResource(R.string.recordings_storage_body, RecordingStorage.ROOT)) },
            confirmButton = {
                TextButton(onClick = { showStorage = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun EmptyRecordings() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.recordings_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.recordings_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SubjectChip(label: String, selected: Boolean, color: CourseColor?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = when {
            selected -> { { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) } }
            color != null -> { { Box(Modifier.size(12.dp).background(color.accent(), CircleShape)) } }
            else -> null
        },
    )
}

@Composable
private fun GroupHeader(subject: String, count: Int, totalBytes: Long, onShareAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding + 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(subject, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.recordings_group_summary, count, TimeUtils.formatSize(totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShareAll) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.recordings_cd_share_group, subject))
        }
    }
}

@Composable
internal fun recordingTitle(startedAt: Long): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdE") }
    return Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern(pattern, locale))
}

private fun clock(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return TimeUtils.minuteToText(t.hour * 60 + t.minute)
}

@Composable
private fun RecordingRow(
    recording: Recording,
    index: Int,
    count: Int,
    liveNow: Boolean,
    isCurrent: Boolean,
    playing: Boolean,
    onToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    val time = clock(recording.startedAt)
    val meta = when {
        liveNow -> "$time · ${stringResource(R.string.recordings_ongoing)}"
        recording.auto -> stringResource(
            R.string.recordings_meta, time,
            TimeUtils.formatDuration(recording.durationMs), TimeUtils.formatSize(recording.sizeBytes),
        )
        else -> stringResource(
            R.string.recordings_meta_manual, time,
            TimeUtils.formatDuration(recording.durationMs), TimeUtils.formatSize(recording.sizeBytes),
        )
    }

    GroupRow(
        index = index,
        count = count,
        selected = isCurrent,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2),
        onClick = if (liveNow) null else onOpenPlayer,
        leading = {
            val label = stringResource(if (playing) R.string.recordings_cd_pause else R.string.recordings_cd_play)
            if (isCurrent) {
                FilledIconButton(onClick = onToggle) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = label)
                }
            } else {
                FilledTonalIconButton(
                    onClick = onToggle,
                    enabled = !liveNow,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = c.surfaceContainerHigh),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = label)
                }
            }
        },
        supporting = {
            if (recording.isSilent) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.MicOff, null, tint = c.error, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.recordings_silent_warning), style = MaterialTheme.typography.bodyMedium, color = c.error)
                }
            }
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recordings_menu_share)) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recordings_menu_rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
    ) {
        RowHeadline(recordingTitle(recording.startedAt))
    }
}

@Composable
private fun MiniPlayer(
    recording: Recording,
    color: CourseColor,
    state: PlaybackState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        color = c.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box {
            Row(
                Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CourseAvatar(color, recording.subject)
                Column(Modifier.weight(1f)) {
                    Text(
                        "${recording.subject} · ${recordingTitle(recording.startedAt)}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.player_mini_meta,
                            TimeUtils.formatDuration(state.positionMs.toLong()),
                            TimeUtils.formatDuration(state.durationMs.toLong()),
                            speedLabel(state.speed),
                        ),
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
            }
            val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.primary)
            )
        }
    }
}

internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

internal val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)
