package dev.iruki.classtime.ui.term

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.ExceptionType
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.data.Term
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.DatePickerModal
import dev.iruki.classtime.ui.common.DetailTopBar
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.common.SectionHeader
import dev.iruki.classtime.ui.common.TimePickerDialog
import dev.iruki.classtime.ui.home.HomeViewModel
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.ui.theme.tones
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREA)

@Composable
private fun longDate(date: LocalDate): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMMdE") }
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermScreen(onBack: () -> Unit) {
    val vm: TermViewModel = hiltViewModel()
    val term by vm.term.collectAsStateWithLifecycle()
    val exceptions by vm.upcomingExceptions.collectAsStateWithLifecycle()
    val courseOptions by vm.courseOptions.collectAsStateWithLifecycle()
    val courseColors by vm.courseColors.collectAsStateWithLifecycle()

    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    var addingCancel by remember { mutableStateOf(false) }
    var addingMakeup by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppTheme.colors.page,
        topBar = { DetailTopBar(stringResource(R.string.term_title), onNavigate = onBack) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.term_fab)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.term_add_cancel)) },
                        onClick = { menuOpen = false; addingCancel = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.term_add_makeup)) },
                        onClick = { menuOpen = false; addingMakeup = true },
                    )
                }
            }
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "term_h") {
                SectionHeader(stringResource(R.string.term_section), Modifier.padding(horizontal = ScreenPadding))
            }
            item(key = "term_group") {
                Column(
                    Modifier.padding(horizontal = ScreenPadding),
                    verticalArrangement = Arrangement.spacedBy(GroupGap),
                ) {
                    TermProgress(term)
                    GroupRow(
                        index = 1, count = 3,
                        onClick = { pickingStart = true },
                        leading = { Icon(Icons.Rounded.Flag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supporting = { RowSupporting(term?.startDate?.let { longDate(it) } ?: stringResource(R.string.value_unset)) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    ) { RowHeadline(stringResource(R.string.term_start)) }
                    GroupRow(
                        index = 2, count = 3,
                        onClick = { pickingEnd = true },
                        leading = { Icon(Icons.Rounded.SportsScore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        supporting = { RowSupporting(term?.endDate?.let { longDate(it) } ?: stringResource(R.string.value_unset)) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    ) { RowHeadline(stringResource(R.string.term_end)) }
                }
            }
            item(key = "term_hint") {
                Row(
                    Modifier.fillMaxWidth().padding(start = ScreenPadding + 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.term_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (term?.startDate != null || term?.endDate != null) {
                        TextButton(onClick = { vm.setTerm(null, null) }) { Text(stringResource(R.string.term_clear)) }
                    }
                }
            }

            item(key = "ex_h") {
                SectionHeader(
                    stringResource(R.string.term_exceptions_title),
                    Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp),
                )
            }
            if (exceptions.isEmpty()) {
                item(key = "ex_empty") {
                    GroupRow(index = 0, count = 1, modifier = Modifier.padding(horizontal = ScreenPadding), minHeight = 56.dp) {
                        RowSupporting(stringResource(R.string.term_no_exceptions))
                    }
                }
            } else {
                itemsIndexed(exceptions, key = { _, e -> e.id }) { i, ex ->
                    ExceptionRow(
                        ex = ex,
                        index = i,
                        count = exceptions.size,
                        colorArgb = ex.courseGroupId?.let { courseColors[it] },
                        onDelete = { vm.remove(ex) },
                    )
                }
            }
        }
    }

    if (pickingStart) {
        DatePickerModal(
            initial = term?.startDate,
            onDismiss = { pickingStart = false },
            onPick = { vm.setTerm(it, term?.endDate) },
        )
    }
    if (pickingEnd) {
        DatePickerModal(
            initial = term?.endDate,
            onDismiss = { pickingEnd = false },
            onPick = { vm.setTerm(term?.startDate, it) },
        )
    }
    if (addingCancel) {
        AddCancelDialog(
            courseOptions = courseOptions,
            onDismiss = { addingCancel = false },
            onHoliday = { date -> vm.addHoliday(date); addingCancel = false },
            onCourseCancel = { date, gid, subject ->
                vm.addCancel(date, gid, subject); addingCancel = false
            },
        )
    }
    if (addingMakeup) {
        AddMakeupDialog(
            courseOptions = courseOptions,
            onDismiss = { addingMakeup = false },
            onConfirm = { date, subject, gid, start, end, auto ->
                vm.addMakeup(date, subject, gid, start, end, auto)
                addingMakeup = false
            },
        )
    }
}

/** 학기 진행 정도: 몇 주차, 종강까지 며칠. 기간이 비어 있으면 안내만. */
@Composable
private fun TermProgress(term: Term?) {
    val today = LocalDate.now()
    val start = term?.startDate
    val end = term?.endDate
    GroupRow(index = 0, count = 3, minHeight = 88.dp) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val week = HomeViewModel.weekOf(term, today)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    when {
                        week != null -> stringResource(R.string.term_week, week)
                        start != null && today.isBefore(start) ->
                            stringResource(R.string.term_not_started, ChronoUnit.DAYS.between(today, start).toInt())
                        end != null && today.isAfter(end) -> stringResource(R.string.term_ended)
                        else -> stringResource(R.string.term_unset)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (week != null && end != null) {
                    Text(
                        stringResource(R.string.term_days_left, ChronoUnit.DAYS.between(today, end).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (start != null && end != null && end.isAfter(start)) {
                val total = ChronoUnit.DAYS.between(start, end).toFloat()
                val done = ChronoUnit.DAYS.between(start, today).toFloat()
                LinearProgressIndicator(
                    progress = { (done / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
            }
        }
    }
}

@Composable
private fun ExceptionRow(ex: ScheduleException, index: Int, count: Int, colorArgb: Int?, onDelete: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val subject = ex.subject.ifBlank { stringResource(R.string.subject_makeup) }
    val (title, meta) = when {
        ex.type == ExceptionType.CANCEL && ex.courseGroupId == null ->
            stringResource(R.string.term_holiday_title, subject) to stringResource(R.string.term_holiday_meta)
        ex.type == ExceptionType.CANCEL ->
            stringResource(R.string.term_cancel_title, subject) to stringResource(R.string.term_cancel_meta)
        else -> stringResource(R.string.term_makeup_title, subject) to stringResource(
            if (ex.autoRecord) R.string.term_makeup_meta_auto else R.string.term_makeup_meta_manual,
            TimeUtils.minuteToText(ex.startMinute),
            TimeUtils.minuteToText(ex.endMinute),
        )
    }
    val (tileBg, tileFg) = if (ex.type == ExceptionType.MAKEUP && colorArgb != null) CourseColors.of(colorArgb).tones()
    else c.surfaceContainerHigh to c.onSurfaceVariant

    GroupRow(
        index = index,
        count = count,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2),
        leading = {
            Column(
                Modifier.size(40.dp).background(tileBg, MaterialTheme.shapes.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(ex.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = tileFg)
                Text(TimeUtils.dayName(ex.date.dayOfWeek.value), style = MaterialTheme.typography.labelSmall, color = tileFg)
            }
        },
        supporting = { RowSupporting(ex.date.format(dateFmt) + " · " + meta) },
        trailing = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.term_cd_delete, title))
            }
        },
    ) { RowHeadline(title) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCancelDialog(
    courseOptions: List<CourseOption>,
    onDismiss: () -> Unit,
    onHoliday: (LocalDate) -> Unit,
    onCourseCancel: (LocalDate, String, String) -> Unit,
) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var wholeDay by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(courseOptions.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.term_add_cancel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date?.format(dateFmt) ?: stringResource(R.string.action_pick_date))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        wholeDay,
                        { wholeDay = true },
                        { Text(stringResource(R.string.term_scope_whole_day)) },
                    )
                    FilterChip(!wholeDay, { wholeDay = false },
                        { Text(stringResource(R.string.term_scope_specific_course)) },
                        enabled = courseOptions.isNotEmpty())
                }
                if (!wholeDay) {
                    CourseDropdown(courseOptions, selected) { selected = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = date != null && (wholeDay || selected != null),
                onClick = {
                    val d = date ?: return@TextButton
                    if (wholeDay) onHoliday(d)
                    else selected?.let { onCourseCancel(d, it.groupId, it.subject) }
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (pickingDate) {
        DatePickerModal(date, { pickingDate = false }, { date = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMakeupDialog(
    courseOptions: List<CourseOption>,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, String, String?, Int, Int, Boolean) -> Unit,
) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(courseOptions.firstOrNull()) }
    var freeSubject by remember { mutableStateOf("") }
    var start by remember { mutableIntStateOf(14 * 60) }
    var end by remember { mutableIntStateOf(15 * 60 + 15) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }

    val subject = selected?.subject ?: freeSubject.trim()
    val valid = date != null && subject.isNotBlank() && end > start

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.term_add_makeup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date?.format(dateFmt) ?: stringResource(R.string.action_pick_date))
                }
                if (courseOptions.isNotEmpty()) {
                    CourseDropdown(courseOptions, selected, allowNone = true) { selected = it }
                }
                if (selected == null) {
                    OutlinedTextField(
                        value = freeSubject,
                        onValueChange = { freeSubject = it },
                        label = { Text(stringResource(R.string.term_subject_field)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.term_time_start, TimeUtils.minuteToText(start)))
                    }
                    OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.term_time_end, TimeUtils.minuteToText(end)))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.term_auto_record), Modifier.weight(1f))
                    AppSwitch(checked = auto, onCheckedChange = { auto = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(date!!, subject, selected?.groupId, start, end, auto)
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    if (pickingDate) DatePickerModal(date, { pickingDate = false }, { date = it })
    if (pickingStart) TimePickerDialog(start, stringResource(R.string.term_pick_start), { pickingStart = false }) {
        start = it; if (end <= it) end = (it + 75).coerceAtMost(23 * 60 + 59); pickingStart = false
    }
    if (pickingEnd) TimePickerDialog(end, stringResource(R.string.term_pick_end), { pickingEnd = false }) {
        end = it; pickingEnd = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDropdown(
    options: List<CourseOption>,
    selected: CourseOption?,
    allowNone: Boolean = false,
    onSelect: (CourseOption?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.subject
                ?: (if (allowNone) stringResource(R.string.term_course_manual_entry) else ""),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.term_course_link_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.term_course_manual_entry)) },
                    onClick = { onSelect(null); expanded = false },
                )
            }
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.subject) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}
