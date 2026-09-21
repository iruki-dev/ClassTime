package dev.iruki.classtime.ui.term

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.ExceptionType
import dev.iruki.classtime.data.ScheduleException
import androidx.hilt.navigation.compose.hiltViewModel
import dev.iruki.classtime.ui.common.DatePickerModal
import dev.iruki.classtime.ui.common.TimePickerDialog
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermScreen(onBack: () -> Unit) {
    val vm: TermViewModel = hiltViewModel()
    val term by vm.term.collectAsStateWithLifecycle()
    val exceptions by vm.upcomingExceptions.collectAsStateWithLifecycle()
    val courseOptions by vm.courseOptions.collectAsStateWithLifecycle()

    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    var addingCancel by remember { mutableStateOf(false) }
    var addingMakeup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.term_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.term_period_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.term_period_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.term_start_label,
                            term?.startDate?.format(dateFmt) ?: stringResource(R.string.value_unset),
                        )
                    )
                }
                OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.term_end_label,
                            term?.endDate?.format(dateFmt) ?: stringResource(R.string.value_unset),
                        )
                    )
                }
            }
            if (term?.startDate != null || term?.endDate != null) {
                TextButton(onClick = { vm.setTerm(null, null) }) {
                    Text(stringResource(R.string.term_clear))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.term_exceptions_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addingCancel = true }) {
                    Text(stringResource(R.string.term_add_cancel))
                }
                OutlinedButton(onClick = { addingMakeup = true }) {
                    Text(stringResource(R.string.term_add_makeup))
                }
            }

            if (exceptions.isEmpty()) {
                Text(
                    stringResource(R.string.term_no_exceptions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                exceptions.forEach { ex -> ExceptionRow(ex) { vm.remove(ex) } }
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

@Composable
private fun ExceptionRow(ex: ScheduleException, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp).height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (ex.type == ExceptionType.CANCEL) R.string.term_type_cancel
                            else R.string.term_type_makeup
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ex.type == ExceptionType.CANCEL)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(ex.date.format(dateFmt), style = MaterialTheme.typography.bodyMedium)
                }
                val detail = buildString {
                    append(
                        ex.subject.ifBlank {
                            stringResource(
                                if (ex.courseGroupId == null) R.string.term_scope_whole_day
                                else R.string.term_scope_course
                            )
                        }
                    )
                    if (ex.type == ExceptionType.MAKEUP) {
                        append("  ")
                        append(TimeUtils.minuteToText(ex.startMinute))
                        append("–")
                        append(TimeUtils.minuteToText(ex.endMinute))
                        append(
                            stringResource(
                                if (ex.autoRecord) R.string.term_auto_suffix
                                else R.string.term_manual_suffix
                            )
                        )
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
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
                    Switch(checked = auto, onCheckedChange = { auto = it })
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
