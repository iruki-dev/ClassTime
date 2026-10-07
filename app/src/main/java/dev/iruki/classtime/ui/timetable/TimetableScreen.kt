package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.data.ExceptionType
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.ui.common.LargeHeader
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseColor
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.ui.theme.tones
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** 1분을 몇 dp 로 그릴지. 9–18시가 한 화면에 들어간다. */
private const val MINUTE_DP = 1.0f
private const val DEFAULT_START = 9 * 60
private const val DEFAULT_END = 18 * 60
private val HourColumn = 24.dp
private val ColumnGap = 3.dp

internal enum class BlockKind { NORMAL, AUTO_OFF, MAKEUP, CANCELLED }

internal data class Block(
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val subject: String,
    val room: String,
    val color: CourseColor,
    val kind: BlockKind,
    val groupId: String?,
)

/** 정규 수업 + 이번 주 예외(휴강·보강)를 화면에 그릴 칸으로 바꾼다. */
internal fun buildBlocks(
    courses: List<Course>,
    exceptions: List<ScheduleException>,
    monday: LocalDate,
): List<Block> {
    val cancels = exceptions.filter { it.type == ExceptionType.CANCEL }
    val regular = courses.map { c ->
        val date = monday.plusDays((c.dayOfWeek - 1).toLong())
        val cancelled = cancels.any { it.date == date && (it.courseGroupId == null || it.courseGroupId == c.groupId) }
        Block(
            dayOfWeek = c.dayOfWeek,
            startMinute = c.startMinute,
            endMinute = c.endMinute,
            subject = c.subject,
            room = c.room,
            color = CourseColors.of(c.colorArgb),
            kind = when {
                cancelled -> BlockKind.CANCELLED
                !c.autoRecord -> BlockKind.AUTO_OFF
                else -> BlockKind.NORMAL
            },
            groupId = c.groupId,
        )
    }
    val makeups = exceptions.filter { it.type == ExceptionType.MAKEUP }.map { e ->
        val linked = courses.firstOrNull { it.groupId == e.courseGroupId }
        Block(
            dayOfWeek = e.date.dayOfWeek.value,
            startMinute = e.startMinute,
            endMinute = e.endMinute,
            subject = e.subject.ifBlank { linked?.subject.orEmpty() },
            room = e.room.ifBlank { linked?.room.orEmpty() },
            color = linked?.let { CourseColors.of(it.colorArgb) } ?: CourseColors.palette.last(),
            kind = BlockKind.MAKEUP,
            groupId = e.courseGroupId,
        )
    }
    return regular + makeups
}

@Composable
fun TimetableScreen(
    onAddCourse: () -> Unit,
    onEditCourse: (String) -> Unit,
    onOpenTerm: () -> Unit,
    onOpenRecordings: () -> Unit,
) {
    val vm: TimetableViewModel = hiltViewModel()
    val courses by vm.courses.collectAsStateWithLifecycle()
    val exceptions by vm.weekExceptions.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val stats by vm.recordingStats.collectAsStateWithLifecycle()

    var openGroup by remember { mutableStateOf<String?>(null) }
    var nowMinute by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMinute = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }

    val monday = TimetableViewModel.weekStart(today)
    val blocks = remember(courses, exceptions, monday) { buildBlocks(courses, exceptions, monday) }
    val groupCount = courses.map { it.groupId }.distinct().size

    Scaffold(
        containerColor = AppTheme.colors.page,
        contentWindowInsets = WindowInsets.statusBars,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCourse,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.timetable_add_course)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            LargeHeader(
                title = stringResource(R.string.timetable_title),
                subtitle = stringResource(R.string.timetable_subtitle, weekRange(monday), groupCount),
            ) {
                IconButton(onClick = onOpenTerm) {
                    Icon(Icons.Rounded.EventBusy, contentDescription = stringResource(R.string.timetable_cd_term))
                }
            }
            Surface(
                color = AppTheme.colors.group,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                if (courses.isEmpty() && blocks.isEmpty()) {
                    EmptyTimetable()
                } else {
                    WeekGrid(
                        blocks = blocks,
                        monday = monday,
                        today = today,
                        nowMinute = nowMinute,
                        holidays = exceptions
                            .filter { it.type == ExceptionType.CANCEL && it.courseGroupId == null }
                            .map { it.date.dayOfWeek.value }
                            .toSet(),
                        onBlock = { b -> b.groupId?.takeIf { gid -> courses.any { it.groupId == gid } }?.let { openGroup = it } },
                    )
                }
            }
        }
    }

    openGroup?.let { gid ->
        val rows = courses.filter { it.groupId == gid }
        // 시트가 열린 사이 과목이 지워졌다면 그리지 않는다(상태는 다음 탭에서 덮어쓴다).
        if (rows.isNotEmpty()) {
            CourseSheet(
                rows = rows,
                stats = stats[rows.first().subject],
                onDismiss = { openGroup = null },
                onEdit = { openGroup = null; onEditCourse(gid) },
                onAutoRecord = { vm.setAutoRecord(gid, it) },
                onCancelNext = { vm.cancelNext(gid, nowMinute); openGroup = null },
                onOpenRecordings = { openGroup = null; onOpenRecordings() },
            )
        }
    }
}

@Composable
private fun weekRange(monday: LocalDate): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd") }
    val f = DateTimeFormatter.ofPattern(pattern, locale)
    return "${monday.format(f)} – ${monday.plusDays(4).format(f)}"
}

@Composable
private fun EmptyTimetable() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CalendarViewWeek, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.timetable_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.timetable_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekGrid(
    blocks: List<Block>,
    monday: LocalDate,
    today: LocalDate,
    nowMinute: Int,
    holidays: Set<Int>,
    onBlock: (Block) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val hasWeekend = blocks.any { it.dayOfWeek >= 6 }
    val days = if (hasWeekend) (1..7).toList() else (1..5).toList()
    val gridStart = (minOf(blocks.minOfOrNull { it.startMinute } ?: DEFAULT_START, DEFAULT_START) / 60) * 60
    val gridEnd = ((maxOf(blocks.maxOfOrNull { it.endMinute } ?: DEFAULT_END, DEFAULT_END) + 59) / 60) * 60
    val totalHeight = ((gridEnd - gridStart) * MINUTE_DP).dp
    val todayDow = if (today.isBefore(monday) || today.isAfter(monday.plusDays(6))) -1 else today.dayOfWeek.value

    Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 8.dp, top = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(HourColumn))
            days.forEach { dow ->
                val isToday = dow == todayDow
                val date = monday.plusDays((dow - 1).toLong())
                Column(
                    Modifier.weight(1f).padding(start = ColumnGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        TimeUtils.dayName(dow),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) c.primary else c.onSurfaceVariant,
                    )
                    Box(
                        Modifier.size(32.dp).background(if (isToday) c.primary else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                isToday -> c.onPrimary
                                dow in holidays -> c.onSurfaceVariant
                                else -> c.onSurface
                            },
                        )
                    }
                    if (dow in holidays) {
                        Text(stringResource(R.string.timetable_holiday), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                    }
                }
            }
        }

        Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 96.dp)) {
            Box(Modifier.width(HourColumn).height(totalHeight)) {
                for (hour in (gridStart / 60) until (gridEnd / 60)) {
                    Text(
                        hour.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.onSurfaceVariant,
                        modifier = Modifier
                            .offset(y = ((hour * 60 - gridStart) * MINUTE_DP).dp - 8.dp)
                            .padding(end = 4.dp)
                            .align(Alignment.TopEnd),
                    )
                }
            }
            days.forEach { dow ->
                Box(Modifier.weight(1f).height(totalHeight).padding(start = ColumnGap)) {
                    for (hour in (gridStart / 60)..(gridEnd / 60)) {
                        Box(
                            Modifier
                                .offset(y = ((hour * 60 - gridStart) * MINUTE_DP).dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(c.surfaceContainerHigh)
                        )
                    }
                    blocks.filter { it.dayOfWeek == dow }.forEach { b ->
                        CourseBlock(b, gridStart) { onBlock(b) }
                    }
                    if (dow == todayDow && nowMinute in gridStart..gridEnd) {
                        val y = ((nowMinute - gridStart) * MINUTE_DP).dp
                        Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(c.primary))
                        Box(Modifier.offset(x = (-4).dp, y = y - 4.dp).size(8.dp).background(c.primary, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(block: Block, gridStart: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (container, onContainer) = block.color.tones()
    val height: Dp = ((block.endMinute - block.startMinute) * MINUTE_DP).dp - ColumnGap
    val top: Dp = ((block.startMinute - gridStart) * MINUTE_DP).dp
    val shape = MaterialTheme.shapes.small
    val bg = when (block.kind) {
        BlockKind.AUTO_OFF -> AppTheme.colors.group
        BlockKind.CANCELLED -> c.surfaceContainerHigh
        else -> container
    }
    val fg = if (block.kind == BlockKind.CANCELLED) c.onSurfaceVariant else onContainer
    val description = stringResource(
        R.string.timetable_cd_block,
        block.subject,
        TimeUtils.dayNameFull(block.dayOfWeek),
        TimeUtils.minuteToText(block.startMinute),
        TimeUtils.minuteToText(block.endMinute),
    ) + when (block.kind) {
        BlockKind.AUTO_OFF -> stringResource(R.string.timetable_cd_auto_off)
        BlockKind.CANCELLED -> stringResource(R.string.timetable_cd_cancelled)
        BlockKind.MAKEUP -> stringResource(R.string.timetable_cd_makeup)
        BlockKind.NORMAL -> ""
    }

    Column(
        Modifier
            .offset(y = top)
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(bg)
            .then(if (block.kind == BlockKind.AUTO_OFF) Modifier.border(1.5.dp, onContainer, shape) else Modifier)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (block.kind == BlockKind.MAKEUP) {
            Text(
                stringResource(R.string.timetable_makeup),
                style = MaterialTheme.typography.labelSmall,
                color = container,
                modifier = Modifier.background(onContainer, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        Text(
            block.subject,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (block.kind == BlockKind.CANCELLED) TextDecoration.LineThrough else null,
        )
        if (height >= 48.dp) {
            Text(
                if (block.kind == BlockKind.CANCELLED) stringResource(R.string.timetable_holiday) else block.room,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (block.kind == BlockKind.AUTO_OFF) {
            Icon(Icons.Rounded.MicOff, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        }
    }
}
