package dev.iruki.classtime.ui.ai

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.ui.platform.LocalContext
import dev.iruki.classtime.ai.BulkPlan
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import dev.iruki.classtime.sync.DriveConfig
import dev.iruki.classtime.sync.DriveError
import androidx.compose.foundation.layout.Arrangement
import dev.iruki.classtime.ai.SummaryModel
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R
import dev.iruki.classtime.data.TranscriptError
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.DetailTopBar
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.recordings.recordingTitle
import dev.iruki.classtime.ui.theme.AppTheme

private typealias LabsRow = @Composable (Int, Int) -> Unit

/**
 * 설정 › 실험적 기능 › AI 텍스트 변환. 맨 위 큰 ‘사용’ 스위치가 기능 전체를 켜고 끈다(Groq 키가
 * 있어야 켤 수 있다). 그 아래 키 · 변환 옵션 · 대기열 · 무료 한도.
 */
@Composable
fun AiLabsScreen(onBack: () -> Unit) {
    val vm: AiLabsViewModel = hiltViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val queue by vm.queueItems.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val summaryUsage by vm.summaryUsage.collectAsStateWithLifecycle()
    var keySheet by remember { mutableStateOf<KeyProvider?>(null) }
    var modelPicker by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf<BulkPlan?>(null) }
    val bulkPlan by vm.bulkPlan.collectAsStateWithLifecycle()
    var driveSheet by remember { mutableStateOf(false) }
    val drive by vm.driveConfig.collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.onDriveConsent(r.data)
    }
    val connectDrive = { vm.connectDrive { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val nothingText = stringResource(R.string.bulk_nothing)
    val hasGroq = config.groqKeyHint != null

    val keyRows = listOf<LabsRow>(
        { i, n ->
            KeyRow(
                i, n, Icons.Rounded.GraphicEq, "Groq",
                summary = keySummary(R.string.ai_key_groq_role, config.groqKeyHint, config.groqRejected, required = true),
                attention = !hasGroq || config.groqRejected,
            ) { keySheet = KeyProvider.GROQ }
        },
        { i, n ->
            KeyRow(
                i, n, Icons.Rounded.Summarize, "Claude",
                summary = keySummary(R.string.ai_key_claude_role, config.claudeKeyHint, config.claudeRejected, required = false),
                attention = config.claudeRejected,
            ) { keySheet = KeyProvider.CLAUDE }
        },
    )

    val convertRows = buildList<LabsRow> {
        add { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { vm.setAutoTranscribe(!config.autoTranscribe) },
                leading = { RowIcon(Icons.Rounded.Bolt) },
                trailing = { AppSwitch(config.autoTranscribe, vm::setAutoTranscribe) },
                minHeight = 56.dp,
            ) { RowHeadline(stringResource(R.string.ai_auto)) }
        }
        if (config.summaryEnabled) {
            add { i, n ->
                GroupRow(
                    index = i, count = n,
                    onClick = { vm.setAutoSummarize(!config.autoSummarize) },
                    leading = { RowIcon(Icons.Rounded.AutoAwesome) },
                    trailing = { AppSwitch(config.autoSummarize, vm::setAutoSummarize) },
                    minHeight = 56.dp,
                ) { RowHeadline(stringResource(R.string.summary_auto)) }
            }
            add { i, n ->
                GroupRow(
                    index = i, count = n,
                    onClick = { modelPicker = true },
                    leading = { RowIcon(Icons.Rounded.Psychology) },
                    supporting = { RowSupporting(stringResource(modelName(config.summaryModel))) },
                    trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
                ) { RowHeadline(stringResource(R.string.summary_model)) }
            }
        }
    }

    val queueAside = stringResource(R.string.ai_queue_count, queue.size)

    val driveStatus = driveStatusText(drive)
    val c = MaterialTheme.colorScheme
    val driveRows = when {
        !drive.connected -> listOf<LabsRow>(
            { i, n ->
                val setup = drive.error == DriveError.SETUP
                GroupRow(
                    index = i, count = n,
                    onClick = { driveSheet = true },
                    leading = { Icon(Icons.Rounded.AddToDrive, contentDescription = null, tint = c.primary) },
                    supporting = {
                        RowSupporting(
                            stringResource(if (setup) R.string.drive_error_setup else R.string.drive_connect_hint),
                            color = if (setup) c.error else c.onSurfaceVariant,
                        )
                    },
                    trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
                ) { RowHeadline(stringResource(R.string.drive_connect)) }
            },
        )
        drive.error == DriveError.RECONNECT -> listOf<LabsRow>(
            { i, n ->
                GroupRow(
                    index = i, count = n,
                    onClick = { connectDrive() },
                    leading = { Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = c.error) },
                    supporting = { RowSupporting(driveStatus, color = c.error) },
                    trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
                ) { RowHeadline(drive.email ?: stringResource(R.string.drive_title)) }
            },
            { i, n -> DisconnectRow(i, n) { confirmDisconnect = true } },
        )
        else -> listOf<LabsRow>(
            { i, n ->
                GroupRow(
                    index = i, count = n,
                    onClick = { vm.syncDriveNow() },
                    leading = {
                        Icon(
                            if (drive.error != null) Icons.Rounded.CloudOff else Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = if (drive.error != null) c.error else c.primary,
                        )
                    },
                    supporting = { RowSupporting(driveStatus, color = if (drive.error != null) c.error else c.onSurfaceVariant) },
                    trailing = {
                        if (drive.running) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.5.dp)
                        else IconButton(onClick = { vm.syncDriveNow() }) {
                            Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.drive_sync_now), tint = c.primary)
                        }
                    },
                ) { RowHeadline(drive.email ?: stringResource(R.string.drive_title)) }
            },
            { i, n ->
                GroupRow(
                    index = i, count = n,
                    onClick = { vm.openDrive() },
                    leading = { RowIcon(Icons.Rounded.FolderOpen) },
                    supporting = { RowSupporting(stringResource(R.string.drive_open_path)) },
                    trailing = { RowIcon(Icons.AutoMirrored.Rounded.OpenInNew) },
                ) { RowHeadline(stringResource(R.string.drive_open)) }
            },
            { i, n -> DisconnectRow(i, n) { confirmDisconnect = true } },
        )
    }

    val toolRows = listOf<LabsRow>(
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = {
                    if (bulkPlan.isEmpty) Toast.makeText(context, nothingText, Toast.LENGTH_SHORT).show() else bulk = bulkPlan
                },
                leading = { RowIcon(Icons.Rounded.DoneAll) },
                supporting = { RowSupporting(bulkCounts(bulkPlan)) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(if (config.summaryEnabled) R.string.bulk_title_summary else R.string.bulk_title_text)) }
        },
    )

    Scaffold(
        containerColor = AppTheme.colors.page,
        topBar = {
            DetailTopBar(stringResource(R.string.ai_title), onNavigate = onBack)
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "badge") { ExperimentalBadge() }
            item(key = "main") {
                MainSwitch(
                    on = config.enabled,
                    enabled = hasGroq && !config.groqRejected,
                    onChange = vm::setSwitchOn,
                    modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
                )
            }
            section("keys", R.string.ai_section_keys, null, keyRows)
            if (config.enabled) {
                section("convert", if (config.summaryEnabled) R.string.ai_section_convert_summary else R.string.ai_section_convert, null, convertRows)
                if (config.summaryEnabled) {
                    item(key = "h_usage") { SectionTitle(R.string.summary_usage_title, null) }
                    item(key = "usage") { UsageCard(summaryUsage, Modifier.padding(horizontal = ScreenPadding)) }
                }
                if (queue.isNotEmpty()) {
                    val rows = queue.map<QueueItem, LabsRow> { q -> { i, n -> QueueRow(i, n, q, onCancel = { vm.cancel(q.recording.id) }) } }
                    section("queue", R.string.ai_section_queue, queueAside, rows)
                }
                section("drive", R.string.drive_title, null, driveRows)
                section("tools", R.string.bulk_section, null, toolRows)
                item(key = "h_quota") { SectionTitle(R.string.ai_section_quota, null) }
                item(key = "quota") { QuotaCard(quota, Modifier.padding(horizontal = ScreenPadding)) }
            }
            item(key = "footer") { Footer() }
        }
    }

    keySheet?.let { provider ->
        KeySheet(
            provider = provider,
            savedHint = if (provider == KeyProvider.GROQ) config.groqKeyHint else config.claudeKeyHint,
            vm = vm,
            onDismiss = { keySheet = null; vm.resetKeyCheck() },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.drive_disconnect_title)) },
            text = { Text(stringResource(R.string.drive_disconnect_body)) },
            confirmButton = { TextButton(onClick = { vm.disconnectDrive(); confirmDisconnect = false }) { Text(stringResource(R.string.drive_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    bulk?.let { plan ->
        BulkDialog(
            plan = plan,
            summarize = config.summaryEnabled && !config.claudeRejected,
            model = config.summaryModel,
            onStart = { vm.runBulk(plan); bulk = null },
            onDismiss = { bulk = null },
        )
    }

    if (driveSheet) {
        DriveConnectSheet(
            onConnect = { driveSheet = false; connectDrive() },
            onDismiss = { driveSheet = false },
        )
    }

    if (modelPicker) {
        ModelPicker(
            current = config.summaryModel,
            onPick = { vm.setSummaryModel(it); modelPicker = false },
            onDismiss = { modelPicker = false },
        )
    }
}

@Composable
private fun keySummary(role: Int, hint: String?, rejected: Boolean, required: Boolean): String {
    val r = stringResource(role)
    return when {
        rejected -> stringResource(R.string.ai_key_rejected, r)
        hint != null -> stringResource(R.string.ai_key_saved, r, hint)
        else -> stringResource(R.string.ai_key_needed, r).takeIf { required } ?: r
    }
}

@Composable
private fun KeyRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    summary: String,
    attention: Boolean,
    onClick: () -> Unit,
) {
    GroupRow(
        index = index, count = count,
        onClick = onClick,
        leading = { RowIcon(icon) },
        supporting = { RowSupporting(summary, color = if (attention) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
    ) { RowHeadline(title) }
}

/** 대기열 한 줄: 무엇을, 어디까지, 언제 이어서. 오른쪽 ×로 취소. */
@Composable
private fun QueueRow(index: Int, count: Int, item: QueueItem, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val t = item.transcript
    val progress = progressOf(t)
    val waiting = t.waitUntil > System.currentTimeMillis()
    val quotaWait = waiting && t.errorEnum == TranscriptError.QUOTA
    val (icon, tint) = when {
        quotaWait -> Icons.Rounded.HourglassTop to AppTheme.colors.caution
        t.stateEnum == TranscriptState.QUEUED -> Icons.Rounded.Schedule to c.onSurfaceVariant
        else -> Icons.Rounded.GraphicEq to c.primary
    }
    val title = "${item.recording.subject} · ${recordingTitle(item.recording.startedAt)}"
    val cancelCd = stringResource(R.string.ai_cd_cancel_job, title)
    GroupRow(
        index = index, count = count,
        leading = { Icon(icon, contentDescription = null, tint = tint) },
        supporting = {
            Text(
                when {
                    quotaWait -> stringResource(R.string.ai_queue_waiting_until, clockText(t.waitUntil))
                    else -> stageLabel(t)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (quotaWait) AppTheme.colors.caution else if (t.stateEnum == TranscriptState.QUEUED) c.onSurfaceVariant else c.primary,
            )
            if (progress != null && !quotaWait) {
                LinearProgressIndicator(
                    progress = { progress },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                )
            }
        },
        trailing = {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = cancelCd) }
        },
    ) { RowHeadline(title) }
}

/** 기능 전체의 메인 스위치(AOSP 설정의 main switch). 켜지면 기본 컨테이너 색. */
@Composable
private fun MainSwitch(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (on) c.primaryContainer else c.surfaceContainerHighest,
        contentColor = if (on) c.onPrimaryContainer else c.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Row(
            Modifier.heightIn(min = 80.dp).padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_use), style = MaterialTheme.typography.titleLarge)
                if (!enabled) {
                    Text(stringResource(R.string.ai_use_hint), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            AppSwitch(checked = on, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun ExperimentalBadge() {
    Row(Modifier.padding(horizontal = ScreenPadding + 4.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                stringResource(R.string.ai_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QuotaCard(q: QuotaUse, modifier: Modifier = Modifier) {
    Surface(color = AppTheme.colors.group, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Meter(stringResource(R.string.ai_quota_hour), q.hourUsed, q.hourMax)
            Meter(stringResource(R.string.ai_quota_day), q.dayUsed, q.dayMax)
        }
    }
}

/** Claude 사용량(추정): 이번 달 금액 · 최근 여섯 달 막대 · 지금까지. 정확한 청구액은 Anthropic 콘솔에서. */
@Composable
private fun UsageCard(u: SummaryUsage, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(color = AppTheme.colors.group, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.summary_usage_month), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = c.onSurfaceVariant)
                    Text(dollars(u.monthDollars), style = MaterialTheme.typography.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold))
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.summary_usage_count, u.monthCount), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.summary_usage_tokens, tokens(u.monthTokens)), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            if (u.months.isNotEmpty()) MonthBars(u.months)
            HorizontalDivider(color = c.outlineVariant.copy(alpha = 0.5f))
            Row {
                Text(
                    stringResource(R.string.summary_usage_total, "%.2f".format(u.totalDollars), u.totalCount),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = c.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.summary_usage_note), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = c.onSurfaceVariant)
            }
        }
    }
}

private fun dollars(d: Double) = "$" + "%.2f".format(d)

/** 최근 여섯 달 요금 막대. 이번 달만 진하게. */
@Composable
private fun MonthBars(months: List<MonthBar>) {
    val c = MaterialTheme.colorScheme
    val max = months.maxOf { it.dollars }.takeIf { it > 0 } ?: 1.0
    val parts = months.map { stringResource(R.string.summary_usage_cd_bar, it.month, "%.2f".format(it.dollars)) }
    val cd = stringResource(R.string.summary_usage_cd_bars, parts.joinToString(", "))
    Row(
        Modifier.fillMaxWidth().height(76.dp).clearAndSetSemantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, m ->
            val now = i == months.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val h = (m.dollars / max * 48).coerceAtLeast(4.0).dp
                Box(Modifier.fillMaxWidth().height(h).background(if (now) c.primary else c.secondaryContainer, RoundedCornerShape(6.dp)))
                Text(
                    stringResource(R.string.summary_usage_bar_month, m.month),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (now) c.onSurface else c.onSurfaceVariant,
                )
            }
        }
    }
}

/** 분 단위 사용량 막대. 85% 를 넘으면 주의색. */
@Composable
private fun Meter(label: String, used: Int, max: Int) {
    val ratio = if (max <= 0) 0f else (used.toFloat() / max).coerceIn(0f, 1f)
    val color = if (ratio > 0.85f) AppTheme.colors.caution else MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.ai_minutes, used),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                " / " + stringResource(R.string.ai_minutes, max),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            color = color,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            strokeCap = StrokeCap.Round,
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun Footer() {
    Row(
        Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding + 4.dp, top = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.ai_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: Int, aside: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding + 4.dp, end = ScreenPadding + 4.dp, top = 16.dp)
            .heightIn(min = 40.dp)
            .padding(top = 12.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.section(key: String, title: Int, aside: String?, rows: List<LabsRow>) {
    item(key = "h_$key") { SectionTitle(title, aside) }
    itemsIndexed(rows, key = { i, _ -> "r_${key}_$i" }) { i, row ->
        Box(Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2)) { row(i, rows.size) }
    }
}

@Composable
private fun DisconnectRow(index: Int, count: Int, onClick: () -> Unit) {
    GroupRow(index = index, count = count, onClick = onClick, leading = { RowIcon(Icons.Rounded.LinkOff) }, minHeight = 56.dp) {
        RowHeadline(stringResource(R.string.drive_disconnect))
    }
}

/** ‘한꺼번에’ 줄의 아랫글: 비어 있는 텍스트·요약 수. */
@Composable
private fun bulkCounts(p: BulkPlan): String = when {
    p.transcribe.isNotEmpty() && p.summarize.isNotEmpty() -> stringResource(R.string.bulk_empty_both, p.transcribe.size, p.summarize.size)
    p.transcribe.isNotEmpty() -> stringResource(R.string.bulk_empty_text, p.transcribe.size)
    p.summarize.isNotEmpty() -> stringResource(R.string.bulk_empty_summary, p.summarize.size)
    else -> stringResource(R.string.bulk_all_filled)
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 드라이브 줄의 아랫글: 오류, 동기화 중, 또는 마지막 동기화 시각과 파일 수. */
@Composable
private fun driveStatusText(d: DriveConfig): String = when {
    d.running -> stringResource(R.string.drive_syncing)
    d.error == DriveError.RECONNECT -> stringResource(R.string.drive_error_reconnect)
    d.error == DriveError.SETUP -> stringResource(R.string.drive_error_setup)
    d.error == DriveError.QUOTA -> stringResource(R.string.drive_error_quota)
    d.error == DriveError.OTHER -> stringResource(R.string.drive_error_other)
    d.lastSyncAt > 0 -> stringResource(R.string.drive_synced, clockText(d.lastSyncAt), d.files)
    else -> stringResource(R.string.drive_waiting)
}

/** 요약 모델 고르기. 고른 모델은 다음 요약부터. */
@Composable
private fun ModelPicker(current: SummaryModel, onPick: (SummaryModel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.summary_model)) },
        text = {
            Column(Modifier.selectableGroup()) {
                SummaryModel.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(selected = m == current, role = Role.RadioButton) { onPick(m) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m == current, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(stringResource(modelName(m)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    when (m) {
                                        SummaryModel.OPUS -> R.string.summary_model_opus_hint
                                        SummaryModel.SONNET -> R.string.summary_model_sonnet_hint
                                        SummaryModel.HAIKU -> R.string.summary_model_haiku_hint
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
