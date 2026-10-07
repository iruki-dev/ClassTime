package dev.iruki.classtime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.data.TimetableInference
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.TimeUtils

/** 녹음 기록으로 짐작한 시간표 미리 보기. 이미 있는 과목은 흐리게, 건드리지 않는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RebuildSheet(preview: RebuildState.Preview, onApply: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val newCount = preview.results.count { it.subject !in preview.existingSubjects }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(if (preview.results.isEmpty()) R.string.rebuild_none else R.string.rebuild_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (preview.results.isNotEmpty()) {
                Text(
                    stringResource(R.string.rebuild_found, preview.recordingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                    preview.results.forEachIndexed { i, r ->
                        val exists = r.subject in preview.existingSubjects
                        GroupRow(
                            index = i,
                            count = preview.results.size,
                            color = AppTheme.colors.group,
                            leading = { CourseIconTile(CourseIcons.guess(r.subject)) },
                            supporting = { RowSupporting(slotSummary(r.slots)) },
                            trailing = if (exists) {
                                { Text(stringResource(R.string.rebuild_exists), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant) }
                            } else null,
                        ) {
                            RowHeadline(r.subject, color = if (exists) c.onSurfaceVariant else c.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (newCount > 0) {
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rebuild_apply, newCount), style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** “화 · 목 09:00 – 10:15”. 시간이 다르면 칸마다 따로. */
private fun slotSummary(slots: List<TimetableInference.Slot>): String =
    slots.groupBy { it.startMinute to it.endMinute }.entries.joinToString("  ") { (time, list) ->
        list.joinToString(" · ") { TimeUtils.dayName(it.dayOfWeek) } +
            " " + TimeUtils.minuteToText(time.first) + " – " + TimeUtils.minuteToText(time.second)
    }
