package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.TimeUtils

/** 시간표 칸을 눌렀을 때 뜨는 과목 상세. 자주 하는 일(자동 녹음 끄기, 휴강)을 편집 화면 없이. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseSheet(
    rows: List<Course>,
    stats: Pair<Int, Long>?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onAutoRecord: (Boolean) -> Unit,
    onCancelNext: () -> Unit,
    onOpenRecordings: () -> Unit,
) {
    val first = rows.first()
    val autoRecord = rows.all { it.autoRecord }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CourseIconTile(CourseIcons.of(first.icon, first.subject), size = 56.dp, container = AppTheme.colors.group)
                Column(Modifier.weight(1f)) {
                    Text(first.subject, style = MaterialTheme.typography.titleLarge)
                    val meta = listOf(first.professor, first.room).filter { it.isNotBlank() }
                    if (meta.isNotEmpty()) {
                        Text(
                            if (first.professor.isNotBlank() && first.room.isNotBlank())
                                stringResource(R.string.course_sheet_professor_room, first.professor, first.room)
                            else meta.first(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalIconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.action_edit))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                GroupRow(
                    index = 0, count = 3,
                    leading = { Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supporting = { RowSupporting(stringResource(R.string.course_sheet_slots_detail, rows.size)) },
                ) { RowHeadline(slotSummary(rows)) }
                GroupRow(
                    index = 1, count = 3,
                    onClick = { onAutoRecord(!autoRecord) },
                    leading = { Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supporting = { RowSupporting(stringResource(R.string.course_auto_record_hint)) },
                    trailing = { AppSwitch(checked = autoRecord, onCheckedChange = onAutoRecord) },
                ) { RowHeadline(stringResource(R.string.course_auto_record)) }
                GroupRow(
                    index = 2, count = 3,
                    onClick = onOpenRecordings,
                    leading = { Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supporting = stats?.let { { RowSupporting(TimeUtils.formatSize(it.second)) } },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                ) {
                    RowHeadline(
                        if (stats != null) stringResource(R.string.course_sheet_recordings, stats.first)
                        else stringResource(R.string.course_sheet_recordings_none)
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCancelNext, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Rounded.EventBusy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.course_sheet_cancel_next))
                }
                Button(onClick = onEdit, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_edit))
                }
            }
        }
    }
}

/** “화 · 목 09:00 – 10:15” 처럼 같은 시간끼리 묶어 한 줄로. */
private fun slotSummary(rows: List<Course>): String =
    rows.groupBy { it.startMinute to it.endMinute }
        .entries
        .sortedBy { it.value.minOf { c -> c.dayOfWeek } }
        .joinToString("  ") { (time, cs) ->
            cs.sortedBy { it.dayOfWeek }.joinToString(" · ") { TimeUtils.dayName(it.dayOfWeek) } +
                " " + TimeUtils.minuteToText(time.first) + " – " + TimeUtils.minuteToText(time.second)
        }
