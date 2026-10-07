package dev.iruki.classtime.ui.ai

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
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
    var keySheet by remember { mutableStateOf<KeyService?>(null) }
    var pickModel by remember { mutableStateOf(false) }
    val hasGroq = config.groqKeyHint != null
    val hasNvidia = config.nvidiaKeyHint != null

    val keyRows = listOf<LabsRow>(
        { i, n ->
            KeyRow(
                i, n, Icons.Rounded.GraphicEq, "Groq",
                summary = keySummary(R.string.ai_key_groq_role, config.groqKeyHint, config.groqRejected, required = true),
                attention = !hasGroq || config.groqRejected,
            ) { keySheet = KeyService.GROQ }
        },
        { i, n ->
            KeyRow(
                i, n, Icons.Rounded.AutoFixHigh, "NVIDIA",
                summary = keySummary(R.string.ai_key_nvidia_role, config.nvidiaKeyHint, config.nvidiaRejected, required = false),
                attention = config.nvidiaRejected,
            ) { keySheet = KeyService.NVIDIA }
        },
    )

    val convertRows = listOf<LabsRow>(
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { vm.setAutoTranscribe(!config.autoTranscribe) },
                leading = { RowIcon(Icons.Rounded.Bolt) },
                trailing = { AppSwitch(config.autoTranscribe, vm::setAutoTranscribe) },
                minHeight = 56.dp,
            ) { RowHeadline(stringResource(R.string.ai_auto)) }
        },
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = if (hasNvidia) ({ vm.setCorrect(!config.correct) }) else null,
                leading = { RowIcon(Icons.Rounded.AutoFixHigh) },
                supporting = {
                    RowSupporting(stringResource(if (hasNvidia) R.string.ai_correct_summary else R.string.ai_correct_needs_key))
                },
                trailing = {
                    AppSwitch(checked = hasNvidia && config.correct, onCheckedChange = vm::setCorrect, enabled = hasNvidia)
                },
            ) { RowHeadline(stringResource(R.string.ai_correct)) }
        },
        { i, n ->
            val current = vm.models.collectAsStateWithLifecycle().value
            val name = AiLabsViewModel.modelName(config.model.ifBlank { current.first().id })
            GroupRow(
                index = i, count = n,
                onClick = if (hasNvidia && config.correct) ({ vm.refreshModels(); pickModel = true }) else null,
                leading = { RowIcon(Icons.Rounded.Psychology) },
                supporting = { RowSupporting(name) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(R.string.ai_model)) }
        },
    )

    val queueAside = stringResource(R.string.ai_queue_count, queue.size)

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
                section("convert", R.string.ai_section_convert, null, convertRows)
                if (queue.isNotEmpty()) {
                    val rows = queue.map<QueueItem, LabsRow> { q -> { i, n -> QueueRow(i, n, q, onCancel = { vm.cancel(q.recording.id) }) } }
                    section("queue", R.string.ai_section_queue, queueAside, rows)
                }
                item(key = "h_quota") { SectionTitle(R.string.ai_section_quota, null) }
                item(key = "quota") { QuotaCard(quota, Modifier.padding(horizontal = ScreenPadding)) }
            }
            item(key = "footer") { Footer() }
        }
    }

    keySheet?.let { service ->
        KeySheet(
            service = service,
            savedHint = if (service == KeyService.GROQ) config.groqKeyHint else config.nvidiaKeyHint,
            vm = vm,
            onDismiss = { keySheet = null; vm.resetKeyCheck() },
        )
    }
    if (pickModel) {
        ModelDialog(
            models = vm.models.collectAsStateWithLifecycle().value,
            selected = config.model.ifBlank { vm.models.value.first().id },
            onDismiss = { pickModel = false },
            onPick = { vm.setModel(it); pickModel = false },
        )
    }
}

@Composable
private fun keySummary(role: Int, hint: String?, rejected: Boolean, required: Boolean): String {
    val r = stringResource(role)
    return when {
        rejected -> stringResource(R.string.ai_key_rejected, r)
        hint != null -> stringResource(R.string.ai_key_saved, r, hint)
        required -> stringResource(R.string.ai_key_needed, r)
        else -> stringResource(R.string.ai_key_optional, r)
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
        t.stateEnum == TranscriptState.CORRECTING -> Icons.Rounded.AutoFixHigh to c.primary
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
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
