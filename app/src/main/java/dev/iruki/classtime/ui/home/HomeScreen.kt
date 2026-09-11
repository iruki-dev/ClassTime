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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.Session
import dev.iruki.classtime.data.StandbyState
import dev.iruki.classtime.ui.classTimeViewModel
import dev.iruki.classtime.util.SetupIssue
import dev.iruki.classtime.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    onOpenTimetable: () -> Unit,
) {
    val vm: HomeViewModel = classTimeViewModel()
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
                    text = "시간표가 비어 있습니다. 수업을 등록하면 자동 녹음과 자동 라벨링이 켜집니다.",
                    actionLabel = "시간표 만들기",
                    onAction = onOpenTimetable,
                )
            }
            when (termPhase) {
                TermPhase.BEFORE -> InfoCard("개강 전입니다. 개강일부터 자동 녹음이 시작됩니다.")
                TermPhase.AFTER -> InfoCard("종강일이 지났습니다. 자동 녹음이 꺼져 있습니다. (수동 녹음은 계속 가능)")
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

            Text(
                text = when {
                    status.active && status.auto -> "‘${status.subject}’ 자동 녹음 중 · ${TimeUtils.formatDuration(elapsed)}"
                    status.active -> "‘${status.subject}’ 녹음 중 · ${TimeUtils.formatDuration(elapsed)}"
                    currentSubject != null -> "지금은 ‘$currentSubject’ 시간입니다. 누르면 이 이름으로 저장됩니다."
                    else -> "시간표에 없는 시간입니다. 누르면 이름을 물어봅니다."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- 오늘의 수업 ---
            Text("오늘 (${TimeUtils.dayName(TimeUtils.todayDowValue())}요일)",
                style = MaterialTheme.typography.titleMedium)

            if (todaySessions.isEmpty()) {
                Text(
                    if (termPhase == TermPhase.DURING || termPhase == TermPhase.NONE)
                        "오늘은 열리는 수업이 없습니다." else "오늘은 학기 중이 아닙니다.",
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
                    Text("저장 위치", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "내장 저장소 / Music / ${RecordingStorage.ROOT} / <과목명>",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "USB 케이블로 PC 에 연결하면 이 폴더가 그대로 보입니다. 과목 폴더째 복사하세요.",
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
            title = { Text("무슨 수업인가요?") },
            text = {
                Column {
                    Text(
                        "지금은 시간표에 없는 시간이라 이름을 붙일 수 없습니다. 비워두면 ‘기타’ 폴더에 저장됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = labelText,
                        onValueChange = { labelText = it },
                        label = { Text("과목명") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.startManual(labelText.trim().ifBlank { null })
                    askLabel = false
                }) { Text("녹음 시작") }
            },
            dismissButton = { TextButton(onClick = { askLabel = false }) { Text("취소") } },
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
                    contentDescription = if (active) "녹음 정지" else "녹음 시작",
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
                text = session.subject + if (session.isMakeup) "  (보강)" else "",
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
                inProgress -> "진행 중"
                !session.autoRecord -> "자동 꺼짐"
                done -> "종료"
                else -> "예약됨"
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
                            ready -> "대기 모드 켜짐 — 자동 녹음 준비 완료"
                            enabled -> "대기 모드 준비 중"
                            else -> "대기 모드 꺼짐 — 자동 녹음이 무음이 됩니다"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            ready -> "수업 시간이 되면 앱이 닫혀 있어도 소리까지 정상적으로 녹음됩니다."
                            enabled -> "이 화면을 잠깐 열어 두면 준비가 끝납니다."
                            else -> "안드로이드는 녹음이 시작되는 순간 앱이 화면에 없으면 마이크를 " +
                                "막습니다. 대기 모드는 앱이 열려 있는 동안 미리 마이크를 확보해 둡니다."
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
                issue.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = onContainer)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(issue.actionLabel) }
        }
    }
}
