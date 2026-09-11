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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.data.ExceptionType
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.ui.classTimeViewModel
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
    val vm: TermViewModel = classTimeViewModel()
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
                title = { Text("학기 · 휴강 · 보강") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
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
            Text("학기 기간", style = MaterialTheme.typography.titleMedium)
            Text(
                "개강일 전과 종강일 후에는 자동 녹음이 잡히지 않습니다. 비워두면 항상 켜집니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
                    Text("개강  " + (term?.startDate?.format(dateFmt) ?: "미설정"))
                }
                OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
                    Text("종강  " + (term?.endDate?.format(dateFmt) ?: "미설정"))
                }
            }
            if (term?.startDate != null || term?.endDate != null) {
                TextButton(onClick = { vm.setTerm(null, null) }) { Text("학기 기간 해제") }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "다가오는 휴강 · 보강",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addingCancel = true }) { Text("휴강 추가") }
                OutlinedButton(onClick = { addingMakeup = true }) { Text("보강 추가") }
            }

            if (exceptions.isEmpty()) {
                Text(
                    "등록된 예외가 없습니다.",
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
                        if (ex.type == ExceptionType.CANCEL) "휴강" else "보강",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (ex.type == ExceptionType.CANCEL)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(ex.date.format(dateFmt), style = MaterialTheme.typography.bodyMedium)
                }
                val detail = buildString {
                    append(ex.subject.ifBlank { if (ex.courseGroupId == null) "전체(공휴일)" else "과목" })
                    if (ex.type == ExceptionType.MAKEUP) {
                        append("  ")
                        append(TimeUtils.minuteToText(ex.startMinute))
                        append("–")
                        append(TimeUtils.minuteToText(ex.endMinute))
                        append(if (ex.autoRecord) " · 자동" else " · 수동")
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "삭제")
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
        title = { Text("휴강 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date?.format(dateFmt) ?: "날짜 선택")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(wholeDay, { wholeDay = true }, { Text("전체(공휴일)") })
                    FilterChip(!wholeDay, { wholeDay = false }, { Text("특정 과목") },
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
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
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
        title = { Text("보강 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date?.format(dateFmt) ?: "날짜 선택")
                }
                if (courseOptions.isNotEmpty()) {
                    CourseDropdown(courseOptions, selected, allowNone = true) { selected = it }
                }
                if (selected == null) {
                    OutlinedTextField(
                        value = freeSubject,
                        onValueChange = { freeSubject = it },
                        label = { Text("과목명") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
                        Text("시작 ${TimeUtils.minuteToText(start)}")
                    }
                    OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
                        Text("종료 ${TimeUtils.minuteToText(end)}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("자동 녹음", Modifier.weight(1f))
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
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    if (pickingDate) DatePickerModal(date, { pickingDate = false }, { date = it })
    if (pickingStart) TimePickerDialog(start, "시작 시각", { pickingStart = false }) {
        start = it; if (end <= it) end = (it + 75).coerceAtMost(23 * 60 + 59); pickingStart = false
    }
    if (pickingEnd) TimePickerDialog(end, "종료 시각", { pickingEnd = false }) {
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
            value = selected?.subject ?: (if (allowNone) "직접 입력" else ""),
            onValueChange = {},
            readOnly = true,
            label = { Text("연결할 과목") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("직접 입력") }, onClick = { onSelect(null); expanded = false })
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
