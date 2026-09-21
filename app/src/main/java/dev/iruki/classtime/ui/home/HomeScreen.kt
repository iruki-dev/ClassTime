package dev.iruki.classtime.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.Session
import dev.iruki.classtime.data.StandbyState
import androidx.hilt.navigation.compose.hiltViewModel
import dev.iruki.classtime.util.SetupIssue
import dev.iruki.classtime.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    onOpenTimetable: () -> Unit,
) {
    val vm: HomeViewModel = hiltViewModel()
    val status by vm.status.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedMs.collectAsStateWithLifecycle()
    val todaySessions by vm.todaySessions.collectAsStateWithLifecycle()
    val currentSubject by vm.currentSubject.collectAsStateWithLifecycle()
    val hasAnyCourse by vm.hasAnyCourse.collectAsStateWithLifecycle()
    val termPhase by vm.termPhase.collectAsStateWithLifecycle()
    val standby by vm.standby.collectAsStateWithLifecycle()
    val standbyEnabled by vm.standbyEnabled.collectAsStateWithLifecycle()
    val hasAutoCourse by vm.hasAutoCourse.collectAsStateWithLifecycle()

    var askLabel by remember { mutableStateOf(false) }
    var labelText by remember { mutableStateOf("") }

    LaunchedEffect(todaySessions) { vm.refreshCurrentSubject() }

    Scaffold(topBar = { TopAppBar(title = { Text("ClassTime") }) }) { inner ->
        Column(
            Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- 설정 점검 ---
            setupIssues.forEach { issue ->
                SetupIssueCard(issue = issue, onAction = { onResolveIssue(issue) })
            }
            if (hasAutoCourse) {
                StandbyCard(
                    enabled = standbyEnabled,
                    state = standby,
                    onToggle = vm::setStandbyEnabled,
                )
            }
            if (!hasAnyCourse) {
                WarningCard(
                    text = stringResource(R.string.home_empty_timetable),
                    actionLabel = stringResource(R.string.home_empty_timetable_action),
                    onAction = onOpenTimetable,
                )
            }
            when (termPhase) {
                TermPhase.BEFORE -> InfoCard(stringResource(R.string.home_term_before))
                TermPhase.AFTER -> InfoCard(stringResource(R.string.home_term_after))
                else -> Unit
            }

            // --- 녹음 버튼 ---
            RecordButton(
                active = status.active,
                onClick = {
                    if (status.active) {
                        vm.stop()
                    } else if (currentSubject != null) {
                        vm.startManual(null)
                    } else {
                        labelText = ""
                        askLabel = true
                    }
                },
            )

            // 위임 프로퍼티(currentSubject)는 스마트 캐스트가 되지 않아 지역 변수로 받는다.
            val subjectNow = currentSubject
            Text(
                text = when {
                    status.active && status.auto -> stringResource(
                        R.string.home_status_auto_recording,
                        status.subject,
                        TimeUtils.formatDuration(elapsed),
                    )
                    status.active -> stringResource(
                        R.string.home_status_recording,
                        status.subject,
                        TimeUtils.formatDuration(elapsed),
                    )
                    subjectNow != null ->
                        stringResource(R.string.home_status_in_class, subjectNow)
                    else -> stringResource(R.string.home_status_off_schedule)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- 오늘의 수업 ---
            Text(
                stringResource(
                    R.string.home_today_header,
                    TimeUtils.dayNameFull(TimeUtils.todayDowValue()),
                ),
                style = MaterialTheme.typography.titleMedium,
            )

            if (todaySessions.isEmpty()) {
                Text(
                    if (termPhase == TermPhase.DURING || termPhase == TermPhase.NONE) {
                        stringResource(R.string.home_no_class_today)
                    } else {
                        stringResource(R.string.home_not_in_term)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val nowMin = TimeUtils.nowMinuteOfDay()
                todaySessions.forEach { session ->
                    TodaySessionRow(session = session, nowMinute = nowMin)
                }
            }

            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.home_storage_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.home_storage_path, RecordingStorage.ROOT),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.home_storage_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (askLabel) {
        AlertDialog(
            onDismissRequest = { askLabel = false },
            title = { Text(stringResource(R.string.home_ask_label_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.home_ask_label_body,
                            stringResource(R.string.subject_unknown),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = { labelText = it },
                        label = { Text(stringResource(R.string.home_ask_label_field)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.startManual(labelText.trim().ifBlank { null })
                    askLabel = false
                }) { Text(stringResource(R.string.home_ask_label_start)) }
            },
            dismissButton = {
                TextButton(onClick = { askLabel = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordButton(active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = bg,
            modifier = Modifier.size(140.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = stringResource(
                        if (active) R.string.home_cd_stop_recording
                        else R.string.home_cd_start_recording
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
    }
}

@Composable
private fun TodaySessionRow(session: Session, nowMinute: Int) {
    val inProgress = nowMinute in session.startMinute..session.endMinute
    val done = nowMinute > session.endMinute

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 36.dp)
                .clip(CircleShape)
                .background(Color(session.colorArgb))
        )
        Column(Modifier.weight(1f)) {
            Text(
                // 보강은 과목명이 비어 있을 수 있다. 그때는 '보강' 자체를 이름으로 쓴다.
                text = if (session.subject.isBlank()) {
                    stringResource(R.string.subject_makeup)
                } else {
                    session.subject + if (session.isMakeup) {
                        stringResource(
                            R.string.home_session_makeup_suffix,
                            stringResource(R.string.subject_makeup),
                        )
                    } else ""
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (inProgress) FontWeight.Bold else FontWeight.Normal,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${TimeUtils.minuteToText(session.startMinute)}–${TimeUtils.minuteToText(session.endMinute)}" +
                    (if (session.room.isNotBlank()) " · ${session.room}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when {
                inProgress -> stringResource(R.string.home_session_in_progress)
                !session.autoRecord -> stringResource(R.string.home_session_auto_off)
                done -> stringResource(R.string.home_session_done)
                else -> stringResource(R.string.home_session_scheduled)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (inProgress) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * 대기 모드 카드.
 *
 * 안드로이드는 녹음 서비스가 포그라운드로 올라오는 **그 순간** 앱이 화면에 있었는지로
 * 마이크 접근 권한을 고정한다. 수업 시간 알람은 백그라운드에서 울리므로, 앱이 열려 있는
 * 동안 미리 서비스를 띄워 권한을 잡아 두는 것이 자동 녹음에 소리가 들어오는 유일한 방법이다.
 */
@Composable
private fun StandbyCard(enabled: Boolean, state: StandbyState, onToggle: (Boolean) -> Unit) {
    val ready = enabled && state.readyForSilentFreeRecording
    val container = if (ready) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (ready) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onErrorContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ready -> stringResource(R.string.home_standby_on)
                            enabled -> stringResource(R.string.home_standby_preparing)
                            else -> stringResource(R.string.home_standby_off)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            ready -> stringResource(R.string.home_standby_on_detail)
                            enabled -> stringResource(R.string.home_standby_preparing_detail)
                            else -> stringResource(R.string.home_standby_off_detail)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun WarningCard(text: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 설정 점검 카드. 심각한 항목(마이크 차단 등)은 오류 색으로, 나머지는 보통 색으로 보여준다.
 */
@Composable
private fun SetupIssueCard(issue: SetupIssue, onAction: () -> Unit) {
    val container = if (issue.critical) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (issue.critical) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(issue.title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(issue.detail), style = MaterialTheme.typography.bodySmall, color = onContainer)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(stringResource(issue.actionLabel)) }
        }
    }
}
