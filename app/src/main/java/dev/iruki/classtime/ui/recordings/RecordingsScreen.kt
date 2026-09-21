package dev.iruki.classtime.ui.recordings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.Recording
import androidx.hilt.navigation.compose.hiltViewModel
import dev.iruki.classtime.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen() {
    val vm: RecordingsViewModel = hiltViewModel()
    val groups by vm.grouped.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val recordingActive by vm.recordingActive.collectAsStateWithLifecycle()

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    var deleting by remember { mutableStateOf<Recording?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.recordings_title)) }) }
    ) { inner ->
        if (groups.isEmpty()) {
            Box(
                Modifier.padding(inner).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.recordings_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(inner).fillMaxSize()) {
            groups.forEach { (subject, recordings) ->
                item(key = "header_$subject") {
                    GroupHeader(
                        subject = subject,
                        count = recordings.size,
                        totalBytes = recordings.sumOf { it.sizeBytes },
                        expanded = expanded[subject] ?: true,
                        onToggle = { expanded[subject] = !(expanded[subject] ?: true) },
                        onShareAll = { vm.shareAll(recordings) },
                    )
                }
                if (expanded[subject] ?: true) {
                    items(recordings, key = { it.id }) { recording ->
                        // 실제로 녹음 진행 중인 그 한 건만 '녹음 중'으로 잠근다.
                        val liveNow = recording.ongoing && recordingActive
                        RecordingRow(
                            recording = recording,
                            liveNow = liveNow,
                            playing = playback.recordingId == recording.id && playback.playing,
                            isCurrent = playback.recordingId == recording.id,
                            positionMs = playback.positionMs,
                            playerDurationMs = playback.durationMs,
                            onToggle = { vm.toggle(recording) },
                            onSeek = { vm.seekTo(it) },
                            onShare = { vm.share(recording) },
                            onRename = { renaming = recording },
                            onDelete = { deleting = recording },
                        )
                    }
                }
                item(key = "divider_$subject") { HorizontalDivider() }
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
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    deleting?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body, rec.fileName)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(rec); deleting = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun GroupHeader(
    subject: String,
    count: Int,
    totalBytes: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShareAll: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    R.string.recordings_group_summary,
                    count,
                    TimeUtils.formatSize(totalBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShareAll) {
            Icon(
                Icons.Filled.Share,
                contentDescription = stringResource(R.string.recordings_cd_share_group, subject),
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.recordings_cd_collapse else R.string.recordings_cd_expand
            ),
        )
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    liveNow: Boolean,
    playing: Boolean,
    isCurrent: Boolean,
    positionMs: Int,
    playerDurationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle, enabled = !liveNow) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.recordings_cd_pause else R.string.recordings_cd_play
                    ),
                )
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    text = recording.fileName.removeSuffix(".m4a"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append(TimeUtils.listStamp(recording.startedAt))
                    if (liveNow) {
                        append(stringResource(R.string.recordings_ongoing_suffix))
                    } else {
                        if (recording.durationMs > 0) {
                            append(" · ${TimeUtils.formatDuration(recording.durationMs)}")
                        }
                        append(" · ${TimeUtils.formatSize(recording.sizeBytes)}")
                        append(
                            stringResource(
                                if (recording.auto) R.string.recordings_auto_suffix
                                else R.string.recordings_manual_suffix
                            )
                        )
                    }
                }
                if (recording.isSilent) {
                    Text(
                        stringResource(R.string.recordings_silent_warning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.recordings_cd_more),
                    )
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
        }

        if (isCurrent && playerDurationMs > 0) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, playerDurationMs.toFloat()),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..playerDurationMs.toFloat(),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    TimeUtils.formatDuration(positionMs.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    TimeUtils.formatDuration(playerDurationMs.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else if (liveNow) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp)
            )
        }
    }
}
