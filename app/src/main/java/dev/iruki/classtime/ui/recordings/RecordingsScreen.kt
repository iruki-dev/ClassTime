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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.ui.ai.ReconvertDialog
import dev.iruki.classtime.ui.ai.TranscriptBadge
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.LargeHeader
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.share.ExportDialog
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.SystemScreens
import dev.iruki.classtime.util.TimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecordingsScreen(onOpenPlayer: () -> Unit, onOpenText: (Long) -> Unit) {
    val vm: RecordingsViewModel = hiltViewModel()
    val groups by vm.grouped.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val recordingActive by vm.recordingActive.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val icons by vm.subjectIcons.collectAsStateWithLifecycle()
    val transcripts by vm.transcripts.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }
    var exporting by remember { mutableStateOf<List<Recording>?>(null) }
    var reconverting by remember { mutableStateOf<Recording?>(null) }

    val all = groups.flatMap { it.second }
    val visible = if (filter.isEmpty()) groups else groups.filter { it.first in filter }

    Scaffold(
        containerColor = AppTheme.colors.page,
        contentWindowInsets = WindowInsets.statusBars,
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") {
                    LargeHeader(
                        title = stringResource(R.string.recordings_title),
                        subtitle = if (all.isEmpty()) null else stringResource(
                            R.string.recordings_subtitle,
                            all.size,
                            TimeUtils.formatSize(all.sumOf { it.sizeBytes }),
                        ),
                    ) {
                        IconButton(onClick = { SystemScreens.openRecordingsFolder(context) }) {
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
                                SubjectChip(stringResource(R.string.recordings_filter_all), filter.isEmpty()) { vm.clearFilter() }
                            }
                            items(groups, key = { it.first }) { (subject, _) ->
                                SubjectChip(subject, subject in filter) {
                                    vm.toggleFilter(subject)
                                }
                            }
                        }
                    }
                }

                visible.forEach { (subject, recordings) ->
                    item(key = "header_$subject") {
                        GroupHeader(
                            subject = subject,
                            icon = icons[subject].orEmpty(),
                            count = recordings.size,
                            totalBytes = recordings.sumOf { it.sizeBytes },
                            onShareAll = {
                                if (recordings.any { transcripts[it.id]?.stateEnum == TranscriptState.DONE }) exporting = recordings
                                else vm.export(recordings)
                            },
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
                                onOpenPlayer()
                            },
                            onShare = {
                                if (transcripts[recording.id]?.stateEnum == TranscriptState.DONE) exporting = listOf(recording)
                                else vm.export(listOf(recording))
                            },
                            onRename = { renaming = recording },
                            onDelete = { deleting = recording },
                            transcript = transcripts[recording.id],
                            canConvert = ai.enabled && !recording.isSilent,
                            onConvert = { vm.convert(recording) },
                            onReconvert = { reconverting = recording },
                            onOpenText = { onOpenText(recording.id) },
                        )
                    }
                }
            }
        }
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

    exporting?.let { list ->
        ExportDialog(
            onDismiss = { exporting = null },
            onSend = { audio, text -> vm.export(list, audio, text); exporting = null },
        )
    }

    reconverting?.let { rec ->
        ReconvertDialog(onDismiss = { reconverting = null }, onConfirm = { vm.convert(rec); reconverting = null })
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
private fun SubjectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )
}

@Composable
private fun GroupHeader(subject: String, icon: String, count: Int, totalBytes: Long, onShareAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding + 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CourseIconTile(CourseIcons.of(icon, subject), size = 32.dp)
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
    transcript: Transcript?,
    canConvert: Boolean,
    onConvert: () -> Unit,
    onReconvert: () -> Unit,
    onOpenText: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                TranscriptBadge(transcript)
            }
        },
        trailing = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 실험적 기능: 텍스트가 있거나 진행 중이면 ‘텍스트 보기’, 없으면 ‘텍스트로 변환’.
                    val hasText = transcript != null && transcript.stateEnum != TranscriptState.FAILED
                    if (hasText) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_menu_view_text)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenText() },
                        )
                        if (canConvert && transcript?.stateEnum == TranscriptState.DONE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_menu_reconvert)) },
                                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                                onClick = { menuOpen = false; onReconvert() },
                            )
                        }
                        HorizontalDivider()
                    } else if (canConvert && !liveNow) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_text_convert)) },
                            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                            onClick = { menuOpen = false; onConvert() },
                        )
                        HorizontalDivider()
                    }
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

internal fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

internal val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
