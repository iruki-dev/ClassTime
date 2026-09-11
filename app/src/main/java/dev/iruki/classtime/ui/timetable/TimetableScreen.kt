package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.ui.classTimeViewModel
import dev.iruki.classtime.util.TimeUtils

/** 1분을 몇 dp 로 그릴지. 75분 수업이 약 79dp 가 된다. */
private const val MINUTE_DP = 1.05f
private const val DEFAULT_START = 9 * 60
private const val DEFAULT_END = 18 * 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onAddCourse: () -> Unit,
    onEditCourse: (String) -> Unit,
    onOpenTerm: () -> Unit,
) {
    val vm: TimetableViewModel = classTimeViewModel()
    val courses by vm.courses.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("시간표") },
                actions = {
                    IconButton(onClick = onOpenTerm) {
                        Icon(Icons.Filled.EditCalendar, contentDescription = "학기 · 휴강 · 보강")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCourse) {
                Icon(Icons.Filled.Add, contentDescription = "수업 추가")
            }
        },
    ) { inner ->
        if (courses.isEmpty()) {
            EmptyTimetable(Modifier.padding(inner))
        } else {
            TimetableGrid(
                courses = courses,
                onEditCourse = onEditCourse,
                modifier = Modifier.padding(inner),
            )
        }
    }
}

@Composable
private fun EmptyTimetable(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("아직 등록된 수업이 없습니다", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "오른쪽 아래 + 버튼으로 수업을 추가하면 그 시간에 맞춰 자동으로 녹음이 시작됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimetableGrid(
    courses: List<Course>,
    onEditCourse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 주말 수업이 없으면 월~금만 보여준다.
    val hasWeekend = courses.any { it.dayOfWeek >= 6 }
    val days = if (hasWeekend) (1..7) else (1..5)

    val gridStart = ((courses.minOf { it.startMinute }.coerceAtMost(DEFAULT_START)) / 60) * 60
    val gridEnd = (((courses.maxOf { it.endMinute }.coerceAtLeast(DEFAULT_END)) + 59) / 60) * 60
    val totalMinutes = (gridEnd - gridStart).coerceAtLeast(60)

    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(modifier.fillMaxSize()) {
        // 요일 헤더
        Row(Modifier.fillMaxWidth().padding(start = 44.dp)) {
            days.forEach { dow ->
                Text(
                    text = TimeUtils.dayName(dow),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 시간 눈금
            Box(Modifier.width(44.dp).height((totalMinutes * MINUTE_DP).dp)) {
                for (hour in (gridStart / 60) until (gridEnd / 60)) {
                    Text(
                        text = "%02d".format(hour),
                        modifier = Modifier
                            .offset(y = ((hour * 60 - gridStart) * MINUTE_DP).dp)
                            .padding(end = 6.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }

            days.forEach { dow ->
                Box(
                    Modifier
                        .weight(1f)
                        .height((totalMinutes * MINUTE_DP).dp)
                        .padding(horizontal = 1.dp)
                ) {
                    // 시간 구분선
                    for (hour in (gridStart / 60)..(gridEnd / 60)) {
                        Box(
                            Modifier
                                .offset(y = ((hour * 60 - gridStart) * MINUTE_DP).dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(outline)
                        )
                    }
                    courses.filter { it.dayOfWeek == dow }.forEach { course ->
                        CourseBlock(
                            course = course,
                            gridStart = gridStart,
                            onClick = { onEditCourse(course.groupId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(course: Course, gridStart: Int, onClick: () -> Unit) {
    val height = ((course.endMinute - course.startMinute) * MINUTE_DP).dp
    val top = ((course.startMinute - gridStart) * MINUTE_DP).dp
    val base = Color(course.colorArgb)

    Column(
        Modifier
            .offset(y = top)
            .fillMaxWidth()
            .height(height)
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(base.copy(alpha = if (course.autoRecord) 0.9f else 0.35f))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Text(
            text = course.subject,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (height > 56.dp && course.room.isNotBlank()) {
            Text(
                text = course.room,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!course.autoRecord && height > 40.dp) {
            Text("자동 꺼짐", color = Color.White, fontSize = 9.sp)
        }
    }
}
