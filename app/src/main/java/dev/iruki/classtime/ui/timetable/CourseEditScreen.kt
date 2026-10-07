package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.DetailTopBar
import dev.iruki.classtime.ui.common.TimePickerDialog
import dev.iruki.classtime.ui.common.clockLabel
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.ui.theme.tones
import dev.iruki.classtime.util.TimeUtils

/** 자주 쓰는 수업 길이(분). 한국 대학의 50·75분 교시와 블록 수업. */
private val LENGTHS = listOf(50, 75, 100, 150)

/** 같은 시간에 열리는 요일 묶음. 저장할 때 요일마다 교시 하나로 펼쳐진다. */
internal data class TimeGroup(
    val key: Long,
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
) {
    val valid get() = days.isNotEmpty() && endMinute > startMinute
}

/** 교시 목록을 (시작, 끝)이 같은 것끼리 묶는다. 편집 화면은 이 단위로 다룬다. */
internal fun groupSlots(slots: List<Slot>): List<Pair<Set<Int>, Pair<Int, Int>>> =
    slots.groupBy { it.startMinute to it.endMinute }
        .entries
        .sortedWith(compareBy({ it.value.minOf { s -> s.dayOfWeek } }, { it.key.first }))
        .map { (time, list) -> list.map { it.dayOfWeek }.toSet() to time }

internal fun List<TimeGroup>.toSlots(): List<Slot> =
    flatMap { g -> g.days.sorted().map { Slot(it, g.startMinute, g.endMinute) } }.distinct()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseEditScreen(groupId: String?, onDone: () -> Unit) {
    val vm: TimetableViewModel = hiltViewModel()
    val courses by vm.courses.collectAsStateWithLifecycle()
    val isEdit = groupId != null

    var loaded by remember { mutableStateOf(!isEdit) }
    var subject by remember { mutableStateOf("") }
    var subjectTouched by remember { mutableStateOf(false) }
    var professor by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var autoRecord by remember { mutableStateOf(true) }
    var colorSeed by remember { mutableIntStateOf(CourseColors.default.seed) }
    var colorChosen by remember { mutableStateOf(isEdit) }
    // 빈 값 = 아직 고르지 않음 → 과목명으로 짐작한 아이콘을 보여 주고, 이름이 바뀌면 따라 바뀐다.
    var iconKey by remember { mutableStateOf("") }
    var pickIcon by remember { mutableStateOf(false) }

    var nextKey by remember { mutableLongStateOf(1L) }
    val groups = remember {
        mutableStateListOf(TimeGroup(0L, setOf(TimeUtils.todayDowValue()), 9 * 60, 10 * 60 + 15))
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Long, Boolean>?>(null) } // group key, isStart

    LaunchedEffect(groupId) {
        if (groupId != null) {
            vm.loadGroup(groupId)?.let { g ->
                subject = g.subject
                professor = g.professor
                room = g.room
                autoRecord = g.autoRecord
                colorSeed = CourseColors.of(g.colorArgb).seed
                iconKey = g.icon
                groups.clear()
                groupSlots(g.slots).forEach { (days, time) ->
                    groups.add(TimeGroup(nextKey++, days, time.first, time.second))
                }
                if (groups.isEmpty()) groups.add(TimeGroup(nextKey++, setOf(TimeUtils.todayDowValue()), 9 * 60, 10 * 60 + 15))
            }
            loaded = true
        }
    }
    // 새 과목은 아직 아무도 안 쓴 색으로 시작한다(시간표에서 서로 구분되도록).
    LaunchedEffect(courses) {
        if (!colorChosen && !isEdit) {
            val used = courses.map { CourseColors.of(it.colorArgb).seed }.toSet()
            colorSeed = (CourseColors.palette.firstOrNull { it.seed !in used } ?: CourseColors.default).seed
        }
    }

    val valid = subject.isNotBlank() && groups.isNotEmpty() && groups.all { it.valid }
    val save = {
        vm.saveGroup(
            groupId = groupId,
            subject = subject.trim(),
            professor = professor.trim(),
            room = room.trim(),
            autoRecord = autoRecord,
            colorArgb = colorSeed,
            icon = iconKey,
            slots = groups.toSlots(),
        )
        onDone()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            DetailTopBar(
                title = stringResource(if (isEdit) R.string.course_edit_title else R.string.course_add_title),
                onNavigate = onDone,
                close = true,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                if (isEdit) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
                TextButton(onClick = { subjectTouched = true; if (valid) save() }, enabled = loaded) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    ) { inner ->
        if (!loaded) return@Scaffold
        Column(
            Modifier
                .padding(inner)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        ) {
            val showError = subjectTouched && subject.isBlank()
            val icon = CourseIcons.of(iconKey, subject)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconPickerButton(icon = icon, onClick = { pickIcon = true }, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it; subjectTouched = true },
                label = { Text(stringResource(R.string.course_subject_label)) },
                supportingText = {
                    Text(
                        if (showError) stringResource(R.string.course_subject_error)
                        else stringResource(
                            R.string.course_subject_support,
                            "${subject.trim().ifBlank { "…" }}_${TimeUtils.fileStamp(System.currentTimeMillis())}.m4a",
                        )
                    )
                },
                isError = showError,
                trailingIcon = if (subject.isNotEmpty()) {
                    {
                        IconButton(onClick = { subject = "" }) {
                            Icon(Icons.Rounded.Cancel, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = professor,
                    onValueChange = { professor = it },
                    label = { Text(stringResource(R.string.course_professor_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text(stringResource(R.string.course_room_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(top = 24.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.course_slots_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.course_slots_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            groups.forEachIndexed { index, group ->
                key(group.key) {
                    TimeGroupEditor(
                        group = group,
                        canDelete = groups.size > 1,
                        onChange = { groups[index] = it },
                        onDelete = { groups.remove(group) },
                        onPickStart = { editing = group.key to true },
                        onPickEnd = { editing = group.key to false },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            TextButton(
                onClick = {
                    val last = groups.last()
                    groups.add(TimeGroup(nextKey++, emptySet(), last.startMinute, last.endMinute))
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.course_add_slot))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Surface(
                onClick = { autoRecord = !autoRecord },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.heightIn(min = 72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.course_auto_record), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.course_auto_record_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppSwitch(checked = autoRecord, onCheckedChange = { autoRecord = it })
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.course_color), style = MaterialTheme.typography.titleMedium)
            // 시간표 색. 이름 없이 원만 — 고른 색은 안쪽 체크와 테두리로 알린다(색만으로 구분하지 않게).
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CourseColors.palette.chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { color ->
                            val selected = color.seed == colorSeed
                            val (bg, fg) = color.tones()
                            val label = stringResource(color.label)
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton) {
                                        colorSeed = color.seed
                                        colorChosen = true
                                    }
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .then(
                                            if (selected) Modifier
                                                .border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                                .padding(4.dp)
                                            else Modifier
                                        )
                                        .background(bg, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { (k, isStart) ->
        val index = groups.indexOfFirst { it.key == k }
        val group = groups.getOrNull(index) ?: return@let
        TimePickerDialog(
            initialMinute = if (isStart) group.startMinute else group.endMinute,
            title = stringResource(if (isStart) R.string.course_pick_start else R.string.course_pick_end),
            onDismiss = { editing = null },
            onConfirm = { picked ->
                groups[index] = if (isStart) {
                    // 시작을 옮기면 길이를 유지한다(75분 수업이면 계속 75분).
                    val length = (group.endMinute - group.startMinute).coerceAtLeast(50)
                    group.copy(startMinute = picked, endMinute = (picked + length).coerceAtMost(23 * 60 + 59))
                } else {
                    group.copy(endMinute = picked)
                }
                editing = null
            },
        )
    }

    if (pickIcon) {
        IconPickerSheet(
            selected = iconKey,
            onDismiss = { pickIcon = false },
            onPick = { key ->
                iconKey = key
                pickIcon = false
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.course_delete_title, subject)) },
            text = { Text(stringResource(R.string.course_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    groupId?.let { vm.deleteGroup(it) }
                    confirmDelete = false
                    onDone()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeGroupEditor(
    group: TimeGroup,
    canDelete: Boolean,
    onChange: (TimeGroup) -> Unit,
    onDelete: () -> Unit,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..7).forEach { dow ->
                    val selected = dow in group.days
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(group.copy(days = if (selected) group.days - dow else group.days + dow)) },
                        label = { Text(TimeUtils.dayName(dow)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.course_cd_delete_slot))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(stringResource(R.string.course_start), group.startMinute, onPickStart, Modifier.weight(1f))
            TimeField(stringResource(R.string.course_end), group.endMinute, onPickEnd, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.course_length_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LENGTHS.forEach { len ->
                val selected = group.endMinute - group.startMinute == len
                FilterChip(
                    selected = selected,
                    onClick = { onChange(group.copy(endMinute = (group.startMinute + len).coerceAtMost(23 * 60 + 59))) },
                    label = { Text(stringResource(R.string.course_length_minutes, len)) },
                )
            }
        }
        when {
            group.days.isEmpty() -> ErrorText(stringResource(R.string.course_need_day))
            group.endMinute <= group.startMinute -> ErrorText(stringResource(R.string.course_end_before_start))
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** M3 외곽선 입력과 같은 모양의 시각 선택 칸. ‘오전 9:00’처럼 보여 주고, 누르면 시각 고르기가 열린다. */
@Composable
private fun TimeField(label: String, minute: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(clockLabel(minute), style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
