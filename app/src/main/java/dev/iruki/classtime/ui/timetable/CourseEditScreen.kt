package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.ui.classTimeViewModel
import dev.iruki.classtime.ui.common.TimePickerDialog
import dev.iruki.classtime.util.TimeUtils

private val PALETTE = listOf(
    0xFF2E6C3E, 0xFF1565C0, 0xFF6A1B9A, 0xFFC62828,
    0xFFEF6C00, 0xFF00838F, 0xFF4E342E, 0xFF37474F,
).map { it.toInt() }

/** 편집 중인 교시. key 는 Compose 리스트 렌더링용 안정 식별자. */
private data class SlotDraft(
    val key: Long,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
) {
    val valid get() = endMinute > startMinute
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseEditScreen(groupId: String?, onDone: () -> Unit) {
    val vm: TimetableViewModel = classTimeViewModel()
    val isEdit = groupId != null

    var loaded by remember { mutableStateOf(!isEdit) }
    var subject by remember { mutableStateOf("") }
    var professor by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var autoRecord by remember { mutableStateOf(true) }
    var colorArgb by remember { mutableIntStateOf(PALETTE.first()) }

    var nextKey by remember { mutableLongStateOf(1L) }
    fun newKey() = nextKey++
    val slots = remember {
        mutableStateListOf(
            SlotDraft(0L, TimeUtils.todayDowValue(), 9 * 60, 10 * 60 + 15)
        )
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Pair<Int, Boolean>?>(null) } // index, isStart

    LaunchedEffect(groupId) {
        if (groupId != null) {
            vm.loadGroup(groupId)?.let { g ->
                subject = g.subject
                professor = g.professor
                room = g.room
                autoRecord = g.autoRecord
                colorArgb = g.colorArgb
                slots.clear()
                g.slots.forEach { slots.add(SlotDraft(newKey(), it.dayOfWeek, it.startMinute, it.endMinute)) }
                if (slots.isEmpty()) {
                    slots.add(SlotDraft(newKey(), TimeUtils.todayDowValue(), 9 * 60, 10 * 60 + 15))
                }
            }
            loaded = true
        }
    }

    val valid = subject.isNotBlank() && slots.isNotEmpty() && slots.all { it.valid }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "수업 수정" else "수업 추가") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (isEdit) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "삭제")
                        }
                    }
                },
            )
        }
    ) { inner ->
        if (!loaded) return@Scaffold

        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("과목명 *") },
                supportingText = { Text("녹음 파일 이름과 폴더 이름으로 사용됩니다") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = professor,
                onValueChange = { professor = it },
                label = { Text("교수님") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = it },
                label = { Text("강의실") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("수업 시간", style = MaterialTheme.typography.labelLarge)
            Text(
                "요일마다 시간이 달라도 되고, 같은 요일에 두 번 열려도 됩니다. 필요한 만큼 추가하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            slots.forEachIndexed { index, slot ->
                key(slot.key) {
                    SlotRow(
                        slot = slot,
                        canDelete = slots.size > 1,
                        onDayChange = { slots[index] = slot.copy(dayOfWeek = it) },
                        onEditStart = { editingSlot = index to true },
                        onEditEnd = { editingSlot = index to false },
                        onDelete = { slots.remove(slot) },
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    val last = slots.lastOrNull()
                    slots.add(
                        if (last != null) last.copy(key = newKey())
                        else SlotDraft(newKey(), TimeUtils.todayDowValue(), 9 * 60, 10 * 60 + 15)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("시간 추가")
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("자동 녹음", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "이 시간이 되면 앱이 알아서 녹음을 시작하고 끝나면 멈춥니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoRecord, onCheckedChange = { autoRecord = it })
            }

            Text("색상", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { argb ->
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (colorArgb == argb) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable { colorArgb = argb }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.saveGroup(
                        groupId = groupId,
                        subject = subject.trim(),
                        professor = professor.trim(),
                        room = room.trim(),
                        autoRecord = autoRecord,
                        colorArgb = colorArgb,
                        slots = slots.map { Slot(it.dayOfWeek, it.startMinute, it.endMinute) },
                    )
                    onDone()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (slots.size > 1) "저장 (${slots.size}교시)" else "저장") }
        }
    }

    editingSlot?.let { (index, isStart) ->
        val slot = slots.getOrNull(index) ?: return@let
        TimePickerDialog(
            initialMinute = if (isStart) slot.startMinute else slot.endMinute,
            title = if (isStart) "시작 시각" else "종료 시각",
            onDismiss = { editingSlot = null },
            onConfirm = { picked ->
                slots[index] = if (isStart) {
                    slot.copy(
                        startMinute = picked,
                        endMinute = if (slot.endMinute <= picked)
                            (picked + 75).coerceAtMost(23 * 60 + 59) else slot.endMinute,
                    )
                } else {
                    slot.copy(endMinute = picked)
                }
                editingSlot = null
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("이 과목을 삭제할까요?") },
            text = { Text("모든 교시에서 지워지고 예약된 자동 녹음도 취소됩니다. 이미 녹음된 파일은 그대로 남습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    groupId?.let { vm.deleteGroup(it) }
                    confirmDelete = false
                    onDone()
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun SlotRow(
    slot: SlotDraft,
    canDelete: Boolean,
    onDayChange: (Int) -> Unit,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DayDropdown(slot.dayOfWeek, onDayChange)
                Spacer(Modifier.weight(1f))
                if (canDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "이 시간 삭제")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditStart, modifier = Modifier.weight(1f)) {
                    Text(TimeUtils.minuteToText(slot.startMinute))
                }
                Text("–", Modifier.align(Alignment.CenterVertically))
                OutlinedButton(onClick = onEditEnd, modifier = Modifier.weight(1f)) {
                    Text(TimeUtils.minuteToText(slot.endMinute))
                }
            }
            if (!slot.valid) {
                Text(
                    "종료 시각이 시작보다 빠릅니다.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DayDropdown(dayOfWeek: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("${TimeUtils.dayName(dayOfWeek)}요일")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (1..7).forEach { dow ->
                DropdownMenuItem(
                    text = { Text("${TimeUtils.dayName(dow)}요일") },
                    onClick = { onChange(dow); expanded = false },
                )
            }
        }
    }
}
