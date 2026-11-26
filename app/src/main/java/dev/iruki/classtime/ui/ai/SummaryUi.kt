package dev.iruki.classtime.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.LectureSummary
import dev.iruki.classtime.ai.NoticeKind
import dev.iruki.classtime.ai.SummaryJson
import dev.iruki.classtime.ai.SummaryModel
import dev.iruki.classtime.ai.Timestamps
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryError
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.ui.theme.AppTheme

/**
 * 요약 탭이 그리는 것.
 * @param starts 대본 문단마다의 시작 시각(ms). 요약의 문단 번호를 시각으로 바꾼다.
 * @param textReady 텍스트 변환이 끝났는지. 끝나야 요약할 수 있다.
 */
data class SummaryView(
    val summary: Summary?,
    val content: LectureSummary?,
    val starts: List<Long>,
    val textReady: Boolean,
) {
    val done get() = content != null && summary?.stateEnum == SummaryState.DONE

    companion object {
        fun of(summary: Summary?, starts: List<Long>, textReady: Boolean): SummaryView {
            val content = summary?.takeIf { it.stateEnum == SummaryState.DONE }
                ?.let { runCatching { SummaryJson.parse(it.content, starts.size) }.getOrNull() }
            return SummaryView(summary, content, starts, textReady)
        }
    }
}

/**
 * 강의 요약. 문장마다 원문 시각이 붙어 있고, 누르면 그 자리의 대본(텍스트 탭)과 녹음으로 간다.
 * 요약만 읽어도 강의를 빠짐없이 파악하고, 더 보고 싶은 곳만 원문으로 건너가게 한다.
 */
@Composable
fun SummaryPane(
    view: SummaryView,
    enabled: Boolean,
    model: SummaryModel,
    onSummarize: () -> Unit,
    onCancel: () -> Unit,
    onRef: (Int) -> Unit,
    onGoText: () -> Unit,
    onOpenLabs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().nestedScroll(KeepScrollInside)) {
        val s = view.summary
        when {
            view.done -> Reading(view, onRef)
            s != null && s.stateEnum != SummaryState.DONE -> Status(s, onCancel, onSummarize, onOpenLabs)
            !view.textReady -> Empty(Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.summary_needs_text)) {
                TextButton(onClick = onGoText) { Text(stringResource(R.string.summary_go_text)) }
            }
            !enabled -> Empty(Icons.Rounded.Summarize, stringResource(R.string.summary_needs_key)) {
                TextButton(onClick = onOpenLabs) { Text(stringResource(R.string.summary_open_labs)) }
            }
            else -> Empty(Icons.Rounded.Summarize, stringResource(R.string.summary_none_title), stringResource(modelName(model))) {
                Button(onClick = onSummarize, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.summary_action), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Empty(icon: ImageVector, title: String, subtitle: String? = null, action: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = c.surfaceContainerHigh, shape = RoundedCornerShape(32.dp), modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(48.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.padding(top = 24.dp)) { action() }
    }
}

/** 보내는 중·기다리는 중·실패. */
@Composable
private fun Status(s: Summary, onCancel: () -> Unit, onRetry: () -> Unit, onOpenLabs: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val failed = s.stateEnum == SummaryState.FAILED
    val bg = if (failed) c.errorContainer else c.surfaceContainerHigh
    val fg = if (failed) c.onErrorContainer else c.onSurface
    val sub = if (failed) c.onErrorContainer else c.onSurfaceVariant
    val title = stringResource(
        when (s.stateEnum) {
            SummaryState.QUEUED -> R.string.summary_state_queued
            SummaryState.SUBMITTED -> R.string.summary_state_running
            else -> R.string.summary_state_failed
        }
    )
    val detail = if (failed) stringResource(
        when (s.errorEnum) {
            SummaryError.CLAUDE_KEY -> R.string.summary_fail_key
            SummaryError.NO_TEXT -> R.string.summary_fail_no_text
            SummaryError.BILLING -> R.string.summary_fail_billing
            SummaryError.REFUSED -> R.string.summary_fail_refused
            SummaryError.TOO_LONG -> R.string.summary_fail_too_long
            SummaryError.EXPIRED -> R.string.summary_fail_expired
            SummaryError.SERVER -> R.string.summary_fail_server
            else -> R.string.summary_fail_unknown
        }
    ) else stringResource(R.string.summary_state_batch)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Surface(
            color = bg, contentColor = fg, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (failed) Icon(Icons.Rounded.Error, contentDescription = null, tint = c.error)
                    else if (s.stateEnum == SummaryState.QUEUED) Icon(Icons.Rounded.Schedule, contentDescription = null, tint = c.onSurfaceVariant)
                    else CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = sub, modifier = Modifier.padding(start = 36.dp))
                if (failed && s.errorDetail.isNotBlank()) {
                    Text(
                        s.errorDetail, style = MaterialTheme.typography.bodySmall, color = sub.copy(alpha = 0.8f), maxLines = 2,
                        modifier = Modifier.padding(start = 36.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (failed) {
                        if (s.errorEnum == SummaryError.CLAUDE_KEY || s.errorEnum == SummaryError.REFUSED) {
                            TextButton(onClick = onOpenLabs) { Text(stringResource(R.string.summary_open_labs), color = sub) }
                        }
                        Button(onClick = onRetry) { Text(stringResource(R.string.ai_action_retry)) }
                    } else {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

/** 다 된 요약. */
@Composable
private fun Reading(view: SummaryView, onRef: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val s = view.content ?: return
    val summary = view.summary ?: return
    fun at(refs: List<Int>): Long? = refs.firstOrNull()?.let { view.starts.getOrNull(it) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)) {
        item(key = "head") {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
                if (s.title.isNotBlank()) Text(s.title, style = MaterialTheme.typography.titleLarge)
                if (s.overview.isNotBlank()) {
                    Text(
                        s.overview,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, lineBreak = LineBreak.Paragraph),
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (s.notices.isNotEmpty()) {
            item(key = "notices") {
                Surface(
                    color = AppTheme.colors.cautionContainer,
                    contentColor = AppTheme.colors.onCautionContainer,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        s.notices.forEach { n ->
                            Line(
                                text = n.text,
                                at = at(n.refs),
                                leading = { Icon(noticeIcon(n.kind), contentDescription = stringResource(noticeLabel(n.kind)), modifier = Modifier.size(20.dp)) },
                                stampColor = AppTheme.colors.onCautionContainer,
                                onClick = n.refs.firstOrNull()?.let { r -> { onRef(r) } },
                            )
                        }
                    }
                }
            }
        }
        s.sections.forEachIndexed { i, sec ->
            item(key = "sec$i") {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Line(
                        text = sec.heading,
                        at = at(sec.refs),
                        heading = true,
                        onClick = sec.refs.firstOrNull()?.let { r -> { onRef(r) } },
                    )
                    sec.points.forEach { p ->
                        Line(
                            text = p.text,
                            at = at(p.refs),
                            leading = { Text("•", style = MaterialTheme.typography.bodyLarge, color = c.primary) },
                            onClick = p.refs.firstOrNull()?.let { r -> { onRef(r) } },
                        )
                    }
                }
            }
        }
        if (s.terms.isNotEmpty()) {
            item(key = "terms_h") {
                Text(
                    stringResource(R.string.summary_terms),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(s.terms, key = { "t_" + it.term }) { t ->
                Line(
                    text = t.term,
                    detail = t.definition,
                    at = at(t.refs),
                    onClick = t.refs.firstOrNull()?.let { r -> { onRef(r) } },
                )
            }
        }
        item(key = "foot") {
            HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp), color = c.outlineVariant)
            val model = SummaryModel.of(summary.model)
            val cost = model.batchCost(summary.inputTokens, summary.outputTokens)
            Text(
                stringResource(
                    R.string.summary_footer,
                    stringResource(modelName(model)),
                    tokens(summary.inputTokens + summary.outputTokens),
                    "%.3f".format(cost),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * 요약 한 줄: (앞 표시) 글 · (오른쪽) 원문 시각. 누르면 원문으로.
 * 시각이 없는 줄(번호가 없거나 잘못된 번호)은 누를 수 없다.
 */
@Composable
private fun Line(
    text: String,
    at: Long?,
    onClick: (() -> Unit)?,
    detail: String? = null,
    heading: Boolean = false,
    stampColor: Color = MaterialTheme.colorScheme.primary,
    leading: (@Composable () -> Unit)? = null,
) {
    val stamp = at?.let { Timestamps.format(it) }
    val cd = stamp?.let { stringResource(R.string.summary_cd_ref, text, it) }
    val click = if (at != null) onClick else null
    Surface(
        onClick = click ?: {},
        enabled = click != null,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).then(if (cd != null) Modifier.semantics { contentDescription = cd } else Modifier),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            if (leading != null) {
                Box(Modifier.padding(top = 2.dp, end = 10.dp).width(20.dp), contentAlignment = Alignment.Center) { leading() }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text,
                    style = if (heading) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp, lineBreak = LineBreak.Paragraph),
                    fontWeight = if (detail != null) FontWeight.SemiBold else null,
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (stamp != null) {
                Text(
                    stamp,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = stampColor,
                    modifier = Modifier.padding(start = 12.dp, top = if (heading) 4.dp else 3.dp),
                )
            }
        }
    }
}

private fun noticeIcon(kind: NoticeKind): ImageVector = when (kind) {
    NoticeKind.EXAM -> Icons.Rounded.Quiz
    NoticeKind.ASSIGNMENT -> Icons.Rounded.Assignment
    NoticeKind.SCHEDULE -> Icons.Rounded.Event
    NoticeKind.EMPHASIS -> Icons.Rounded.PriorityHigh
    NoticeKind.OTHER -> Icons.Rounded.Campaign
}

private fun noticeLabel(kind: NoticeKind): Int = when (kind) {
    NoticeKind.EXAM -> R.string.summary_notice_exam
    NoticeKind.ASSIGNMENT -> R.string.summary_notice_assignment
    NoticeKind.SCHEDULE -> R.string.summary_notice_schedule
    NoticeKind.EMPHASIS -> R.string.summary_notice_emphasis
    NoticeKind.OTHER -> R.string.summary_notice_other
}

internal fun modelName(model: SummaryModel): Int = when (model) {
    SummaryModel.OPUS -> R.string.summary_model_opus
    SummaryModel.SONNET -> R.string.summary_model_sonnet
    SummaryModel.HAIKU -> R.string.summary_model_haiku
}

/** 12,345 → “1.2만”, 그 아래는 그대로. */
internal fun tokens(n: Long): String = if (n >= 10_000) "%.1f만".format(n / 10_000.0) else "%,d".format(n)
