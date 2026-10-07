package dev.iruki.classtime.ui.home

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.Session
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.IconTile
import dev.iruki.classtime.ui.common.LargeHeader
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.common.SectionHeader
import dev.iruki.classtime.ui.common.StatusLabel
import dev.iruki.classtime.ui.common.formatSpan
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.common.tileContainer
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.SetupId
import dev.iruki.classtime.util.SetupIssue
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    onOpenTimetable: () -> Unit,
    onAddCourse: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = hiltViewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val level by vm.inputLevel.collectAsStateWithLifecycle()
    val sessions by vm.todaySessions.collectAsStateWithLifecycle()
    val currentSubject by vm.currentSubject.collectAsStateWithLifecycle()
    val hasAnyCourse by vm.hasAnyCourse.collectAsStateWithLifecycle()
    val termPhase by vm.termPhase.collectAsStateWithLifecycle()
    val termWeek by vm.termWeek.collectAsStateWithLifecycle()
    val standby by vm.standby.collectAsStateWithLifecycle()
    val standbyEnabled by vm.standbyEnabled.collectAsStateWithLifecycle()
    val hasAutoCourse by vm.hasAutoCourse.collectAsStateWithLifecycle()
    val recordedToday by vm.recordedToday.collectAsStateWithLifecycle()
    val subjects by vm.subjects.collectAsStateWithLifecycle()

    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(sessions, now / 60_000) { vm.refreshCurrentSubject() }

    // ‘다른 앱 위에 표시’는 보조 수단이라 홈에서 할 일로 올리지 않는다(설정 화면에만).
    val fixes = setupIssues.filter { it.id != SetupId.BACKGROUND_MIC }
    val standbyFix = hasAutoCourse && !standbyEnabled
    val fixCount = fixes.size + if (standbyFix) 1 else 0
    val micIssue = setupIssues.firstOrNull { it.id == SetupId.MICROPHONE }
    val firstRun = hasAnyCourse == false
    val nowMinute = minuteOfDay(now)
    val next = sessions.firstOrNull { it.startMinute > nowMinute }
    val inClass = sessions.firstOrNull { nowMinute in it.startMinute until it.endMinute }

    Scaffold(
        containerColor = AppTheme.colors.page,
        contentWindowInsets = WindowInsets.statusBars,
    ) { inner ->
        LazyColumn(
            Modifier.padding(inner),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "header") {
                LargeHeader(
                    title = stringResource(R.string.home_title),
                    subtitle = dateSubtitle(termWeek),
                ) {
                    // 수동 녹음은 자주 쓰지 않는다. 큰 버튼 대신 설정 옆 아이콘 하나로 둔다.
                    if (!status.active && !firstRun) {
                        IconButton(onClick = {
                            when {
                                micIssue != null -> onResolveIssue(micIssue)
                                currentSubject != null -> vm.startManual(null)
                                else -> showSheet = true
                            }
                        }) {
                            Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.home_cd_record))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.home_cd_settings))
                    }
                }
            }

            item(key = "status") {
                Box(Modifier.padding(horizontal = ScreenPadding)) {
                    when {
                        status.active -> RecordingCard(status, now, level, onExtend = vm::extend, onStop = vm::stop)
                        fixCount > 0 -> AttentionCard(fixCount, next, nowMinute)
                        firstRun -> FirstRunCard(onAddCourse = onAddCourse, onRecordNow = { showSheet = true })
                        hasAnyCourse == true -> ScheduleCard(
                            inClass = inClass,
                            next = next,
                            nowMinute = nowMinute,
                            sessionsToday = sessions.size,
                            termPhase = termPhase,
                            showStandby = hasAutoCourse,
                            standby = standby,
                            standbyEnabled = standbyEnabled,
                            onOpenSettings = onOpenSettings,
                        )
                        else -> Unit
                    }
                }
            }

            if (!status.active && fixCount > 0) {
                item(key = "fix_header") {
                    SectionHeader(
                        stringResource(R.string.home_fix_header),
                        Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp),
                    )
                }
                val rows = buildList<@Composable (Int, Int) -> Unit> {
                    if (standbyFix) add { i, n ->
                        FixRow(
                            index = i, count = n,
                            icon = Icons.Rounded.Mic,
                            title = stringResource(R.string.home_standby_title),
                            detail = stringResource(R.string.home_standby_detail),
                        ) { AppSwitch(checked = false, onCheckedChange = vm::setStandbyEnabled) }
                    }
                    fixes.forEach { issue ->
                        add { i, n ->
                            FixRow(
                                index = i, count = n,
                                icon = issueIcon(issue.id),
                                title = stringResource(issue.title),
                                detail = stringResource(issue.detail),
                            ) {
                                FilledTonalButton(onClick = { onResolveIssue(issue) }) {
                                    Text(stringResource(issue.actionLabel))
                                }
                            }
                        }
                    }
                }
                itemsIndexed(rows, key = { i, _ -> "fix_$i" }) { i, row ->
                    Box(Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2)) { row(i, rows.size) }
                }
            }

            if (firstRun) {
                item(key = "how_header") {
                    SectionHeader(
                        stringResource(R.string.home_how_header),
                        Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp),
                    )
                }
                val steps = listOf(
                    Triple(Icons.Rounded.EditCalendar, R.string.home_how_1_title, R.string.home_how_1_body),
                    Triple(Icons.Rounded.Mic, R.string.home_how_2_title, R.string.home_how_2_body),
                    Triple(Icons.Rounded.FolderOpen, R.string.home_how_3_title, R.string.home_how_3_body),
                )
                itemsIndexed(steps, key = { i, _ -> "how_$i" }) { i, (icon, title, body) ->
                    GroupRow(
                        index = i, count = steps.size,
                        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2),
                        leading = {
                            IconTile(icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        },
                        supporting = { RowSupporting(stringResource(body)) },
                    ) { RowHeadline(stringResource(title)) }
                }
            } else if (sessions.isNotEmpty()) {
                item(key = "today_header") {
                    SectionHeader(
                        stringResource(R.string.home_today_header, sessions.size),
                        Modifier.padding(start = ScreenPadding, end = 8.dp, top = 16.dp),
                    ) {
                        TextButton(onClick = onOpenTimetable) {
                            Text(stringResource(R.string.home_open_timetable))
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                itemsIndexed(sessions, key = { i, s -> "s_${i}_${s.startMinute}_${s.subject}" }) { i, s ->
                    SessionRow(
                        session = s,
                        index = i,
                        count = sessions.size,
                        nowMinute = nowMinute,
                        isNext = s == next,
                        recordingThis = status.active && status.subject == s.subject &&
                            nowMinute in s.startMinute..s.endMinute,
                        recorded = s.subject in recordedToday,
                    )
                }
            }
        }
    }

    if (showSheet) {
        RecordSheet(
            subjects = subjects,
            onDismiss = { showSheet = false },
            onStart = { subject ->
                showSheet = false
                if (micIssue != null) onResolveIssue(micIssue) else vm.startManual(subject)
            },
        )
    }
}

// --- 머리 ---

@Composable
private fun dateSubtitle(week: Int?): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE") }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale))
    return if (week != null) stringResource(R.string.home_subtitle_week, date, week)
    else stringResource(R.string.home_subtitle_date, date)
}

// --- 상태 카드 ---

/** 상태 카드 공통 틀: 모서리 28, 안쪽 여백 20, 상태 줄 → 본문. */
@Composable
private fun StatusCard(
    container: Color,
    content: Color,
    accent: Color,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = accent)
                Text(label, style = MaterialTheme.typography.titleMedium, color = accent)
            }
            body()
        }
    }
}

@Composable
private fun ScheduleCard(
    inClass: Session?,
    next: Session?,
    nowMinute: Int,
    sessionsToday: Int,
    termPhase: TermPhase,
    showStandby: Boolean,
    standby: StandbyState,
    standbyEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val focus = inClass ?: next
    if (focus == null || termPhase == TermPhase.BEFORE || termPhase == TermPhase.AFTER) {
        IdleCard(sessionsToday, termPhase)
        return
    }
    val ready = !showStandby || standby.readyForSilentFreeRecording
    val c = MaterialTheme.colorScheme
    StatusCard(
        container = c.primaryContainer,
        content = c.onPrimaryContainer,
        accent = c.onPrimaryContainer,
        icon = Icons.Rounded.Verified,
        label = stringResource(if (ready) R.string.home_state_ready else R.string.home_state_preparing),
    ) {
        Column(Modifier.padding(top = 20.dp, bottom = 16.dp)) {
            Text(
                if (inClass != null) stringResource(R.string.home_now_label, TimeUtils.minuteToText(inClass.endMinute))
                else stringResource(R.string.home_next_label, formatSpan(focus.startMinute - nowMinute)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                focus.displaySubject(),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                focus.timeAndRoom(),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (inClass != null && !inClass.autoRecord) {
                Text(
                    stringResource(R.string.home_in_class_auto_off),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (showStandby && standbyEnabled) {
            HorizontalDivider(color = c.onPrimaryContainer.copy(alpha = 0.16f))
            Surface(
                onClick = onOpenSettings,
                color = Color.Transparent,
                contentColor = c.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(
                            if (standby.readyForSilentFreeRecording) R.string.home_standby_line_on
                            else R.string.home_standby_line_preparing
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun IdleCard(sessionsToday: Int, termPhase: TermPhase) {
    val c = MaterialTheme.colorScheme
    val (title, detail) = when (termPhase) {
        TermPhase.BEFORE -> stringResource(R.string.home_idle_before_term) to null
        TermPhase.AFTER -> stringResource(R.string.home_idle_after_term) to stringResource(R.string.home_idle_after_term_detail)
        else -> stringResource(if (sessionsToday > 0) R.string.home_idle_done else R.string.home_idle_none) to null
    }
    StatusCard(
        container = AppTheme.colors.group,
        content = c.onSurface,
        accent = c.onSurfaceVariant,
        icon = Icons.Rounded.Bedtime,
        label = stringResource(R.string.home_state_idle),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AttentionCard(count: Int, next: Session?, nowMinute: Int) {
    val colors = AppTheme.colors
    StatusCard(
        container = colors.cautionContainer,
        content = colors.onCautionContainer,
        accent = colors.caution,
        icon = Icons.Rounded.Error,
        label = stringResource(R.string.home_state_attention),
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_attention_title, count), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (next != null) stringResource(
                    R.string.home_attention_next,
                    next.displaySubject(),
                    formatSpan(next.startMinute - nowMinute),
                ) else stringResource(R.string.home_attention_no_next),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.caution,
            )
        }
    }
}

@Composable
private fun RecordingCard(
    status: RecordingStatus,
    now: Long,
    level: Int,
    onExtend: (Int) -> Unit,
    onStop: () -> Unit,
) {
    // 연장 버튼을 누르면 같은 줄이 1·2·3·5·10분 버튼으로 바뀐다. 따로 뜨는 시트는 없다.
    var extendOpen by remember { mutableStateOf(false) }
    val colors = AppTheme.colors
    val elapsed = (now - status.startedAt).coerceAtLeast(0L)
    // 최근 진폭 몇 개만 기억해 막대로 그린다. 한 번의 조용한 표본에 ‘조용해요’가 깜빡이지 않도록.
    val recent = remember { mutableStateListOf<Int>() }
    LaunchedEffect(level) {
        recent.add(level)
        while (recent.size > 10) recent.removeAt(0)
    }
    val quiet = elapsed > 8_000 && (recent.maxOrNull() ?: 0) < QUIET_LEVEL

    StatusCard(
        container = colors.record,
        content = colors.onRecord,
        accent = colors.onRecord,
        icon = Icons.Rounded.RadioButtonChecked,
        label = stringResource(
            if (status.auto) R.string.home_state_recording_auto else R.string.home_state_recording_manual
        ),
    ) {
        Text(
            TimeUtils.formatDuration(elapsed),
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(status.subject, style = MaterialTheme.typography.titleLarge)
        if (status.plannedEndAt > status.startedAt) {
            val total = status.plannedEndAt - status.startedAt
            val remainingMin = ((status.plannedEndAt - now).coerceAtLeast(0) / 60_000).toInt()
            LinearProgressIndicator(
                progress = { (elapsed.toFloat() / total).coerceIn(0f, 1f) },
                color = colors.onRecord,
                trackColor = colors.onRecord.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(
                    stringResource(R.string.home_rec_started_at, TimeUtils.minuteToText(minuteOfDay(status.startedAt))),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (status.extendedMinutes > 0) stringResource(
                        R.string.home_rec_ends_at_extended,
                        TimeUtils.minuteToText(minuteOfDay(status.plannedEndAt)),
                        status.extendedMinutes,
                    ) else stringResource(
                        R.string.home_rec_ends_at,
                        TimeUtils.minuteToText(minuteOfDay(status.plannedEndAt)),
                        formatSpan(remainingMin),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
            Text(
                stringResource(R.string.home_rec_manual_detail),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.onRecord.copy(alpha = 0.22f))
        val levelText = stringResource(if (quiet) R.string.home_rec_level_quiet else R.string.home_rec_level_ok)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = levelText },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LevelBars(recent, colors.onRecord)
            Text(levelText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
        }
        // 정지와 연장은 카드 안에 둔다. 떠 있는 버튼보다 ‘이 녹음’에 대한 동작이라는 것이 분명하다.
        val tonal = ButtonDefaults.buttonColors(
            containerColor = colors.onRecord.copy(alpha = 0.18f),
            contentColor = colors.onRecord,
        )
        val solid = ButtonDefaults.buttonColors(containerColor = colors.onRecord, contentColor = colors.record)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).animateContentSize()) {
            if (extendOpen && status.plannedEndAt > 0L) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledIconButton(
                        onClick = { extendOpen = false },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.onRecord.copy(alpha = 0.18f),
                            contentColor = colors.onRecord,
                        ),
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.home_rec_extend_close)) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        val choices = RecordingService.EXTEND_CHOICES
                        choices.forEachIndexed { i, minutes ->
                            val cd = stringResource(R.string.home_rec_extend_cd, minutes)
                            Button(
                                onClick = { onExtend(minutes); extendOpen = false },
                                colors = solid,
                                shape = connectedShape(i, choices.size),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .semantics { contentDescription = cd },
                            ) { Text("+$minutes", style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.plannedEndAt > 0L) {
                        Button(
                            onClick = { extendOpen = true },
                            colors = tonal,
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) {
                            Icon(Icons.Rounded.MoreTime, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_rec_extend), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Button(
                        onClick = onStop,
                        colors = solid,
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_fab_stop), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** M3 Expressive 연결 버튼 그룹 모양: 양 끝은 완전 원, 안쪽 모서리는 8. */
private fun connectedShape(index: Int, count: Int): RoundedCornerShape {
    val inner = 8.dp
    val outer = 28.dp
    return when (index) {
        0 -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
        count - 1 -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

@Composable
private fun LevelBars(levels: List<Int>, color: Color) {
    Row(
        Modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(10) { i ->
            val v = levels.getOrNull(levels.size - 10 + i) ?: 0
            val fraction = (v / 6_000f).coerceIn(0.15f, 1f)
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp * fraction)
                    .background(color.copy(alpha = if (v > 0) 1f else 0.45f), RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun FirstRunCard(onAddCourse: () -> Unit, onRecordNow: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = AppTheme.colors.group, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(64.dp).background(c.primaryContainer, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.EventNote, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(32.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.home_first_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.home_first_body), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Column {
                Button(
                    onClick = onAddCourse,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_first_add), style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRecordNow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_first_record))
                }
            }
        }
    }
}

// --- 목록 ---

@Composable
private fun FixRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    detail: String,
    action: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    GroupRow(
        index = index,
        count = count,
        minHeight = 88.dp,
        leading = { IconTile(icon, colors.cautionContainer, colors.caution) },
        supporting = { RowSupporting(detail) },
        trailing = action,
    ) { RowHeadline(title) }
}

private fun issueIcon(id: SetupId): ImageVector = when (id) {
    SetupId.MICROPHONE -> Icons.Rounded.Mic
    SetupId.NOTIFICATIONS -> Icons.Rounded.Notifications
    SetupId.EXACT_ALARM -> Icons.Rounded.Schedule
    SetupId.BACKGROUND_MIC -> Icons.Rounded.VerifiedUser
    SetupId.BATTERY -> Icons.Rounded.BatterySaver
}

@Composable
private fun SessionRow(
    session: Session,
    index: Int,
    count: Int,
    nowMinute: Int,
    isNext: Boolean,
    recordingThis: Boolean,
    recorded: Boolean,
) {
    val c = MaterialTheme.colorScheme
    val colors = AppTheme.colors
    val past = nowMinute >= session.endMinute
    val inProgress = nowMinute in session.startMinute until session.endMinute
    GroupRow(
        index = index,
        count = count,
        selected = isNext,
        color = if (recordingThis) colors.recordSubtle else null,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2),
        leading = {
            CourseIconTile(
                CourseIcons.of(session.icon, session.subject),
                container = when {
                    recordingThis || isNext -> colors.group
                    else -> tileContainer()
                },
                content = if (recordingThis) colors.onRecordSubtle else c.onSurfaceVariant,
            )
        },
        supporting = { RowSupporting(session.timeAndRoom()) },
        trailing = {
            when {
                recordingThis -> StatusLabel(stringResource(R.string.home_session_recording), Icons.Rounded.RadioButtonChecked, colors.onRecordSubtle)
                recorded && (past || inProgress) -> StatusLabel(stringResource(R.string.home_session_done), Icons.Rounded.CheckCircle, c.primary)
                inProgress -> StatusLabel(stringResource(R.string.home_session_in_progress), Icons.Rounded.Schedule, c.onSurface)
                past -> StatusLabel(stringResource(R.string.home_session_past), null, c.onSurfaceVariant)
                isNext -> StatusLabel(stringResource(R.string.home_session_next), Icons.Rounded.Schedule, c.onSecondaryContainer, AppTheme.colors.group)
                session.autoRecord -> StatusLabel(stringResource(R.string.home_session_auto), Icons.Rounded.Mic, c.onSurfaceVariant)
                else -> StatusLabel(stringResource(R.string.home_session_auto_off), Icons.Rounded.MicOff, c.onSurfaceVariant)
            }
        },
    ) {
        RowHeadline(
            if (session.isMakeup) stringResource(R.string.home_session_makeup, session.displaySubject())
            else session.displaySubject()
        )
    }
}

@Composable
private fun Session.displaySubject(): String =
    subject.ifBlank { stringResource(R.string.subject_makeup) }

@Composable
private fun Session.timeAndRoom(): String {
    val start = TimeUtils.minuteToText(startMinute)
    val end = TimeUtils.minuteToText(endMinute)
    return if (room.isNotBlank()) stringResource(R.string.home_session_time_room, start, end, room)
    else stringResource(R.string.home_session_time, start, end)
}

private fun minuteOfDay(epochMs: Long): Int {
    val t = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return t.hour * 60 + t.minute
}

/** 이보다 작은 진폭만 계속 들어오면 ‘조용해요’로 안내한다(완전한 무음 판정은 서비스가 따로 한다). */
private const val QUIET_LEVEL = 300
