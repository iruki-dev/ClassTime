package dev.iruki.classtime.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
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
import dev.iruki.classtime.ui.common.keepAll
import kotlinx.coroutines.launch

/** 요약의 바탕이 되는 텍스트가 어디까지 왔는지. */
enum class TextStage { NONE, RUNNING, READY }

/**
 * 요약 탭이 그리는 것.
 * @param starts 대본 문단마다의 시작 시각(ms). 요약의 문단 번호를 시각으로 바꾼다.
 * @param text 텍스트 변환이 어디까지 왔는지. 끝나야 요약할 수 있다.
 */
data class SummaryView(
    val summary: Summary?,
    val content: LectureSummary?,
    val starts: List<Long>,
    val text: TextStage,
) {
    val done get() = content != null && summary?.stateEnum == SummaryState.DONE
    val textReady get() = text == TextStage.READY

    companion object {
        fun of(summary: Summary?, starts: List<Long>, text: TextStage): SummaryView {
            val content = summary?.takeIf { it.stateEnum == SummaryState.DONE }
                ?.let { runCatching { SummaryJson.parse(it.content, starts.size) }.getOrNull() }
            return SummaryView(summary, content, starts, text)
        }
    }
}

/**
 * 강의 요약. 문장마다 원문 시각이 붙어 있고, 누르면 그 자리의 대본(텍스트 탭)과 녹음으로 간다.
 * 요약만 읽어도 강의를 빠짐없이 파악하고, 더 보고 싶은 곳만 원문으로 건너가게 한다.
 *
 * @param enabled Claude 키가 있고 실험적 기능이 켜져 있는지.
 * @param canConvert 텍스트 변환(Groq)을 쓸 수 있는지.
 */
@Composable
fun SummaryPane(
    view: SummaryView,
    enabled: Boolean,
    canConvert: Boolean,
    onSummarize: () -> Unit,
    onConvertThenSummarize: () -> Unit,
    onSummarizeAfterText: () -> Unit,
    onCancel: () -> Unit,
    onRef: (Int) -> Unit,
    onOpenLabs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().nestedScroll(KeepScrollInside)) {
        val s = view.summary
        when {
            view.done -> Reading(view, onRef)
            s != null && s.stateEnum != SummaryState.DONE -> Status(s, view.textReady, onCancel, onSummarize, onOpenLabs)
            !enabled -> Empty(
                Icons.Rounded.Summarize, stringResource(R.string.summary_none_title), stringResource(R.string.summary_needs_key),
                stringResource(R.string.summary_open_labs), Icons.Rounded.Key, filled = false, onClick = onOpenLabs,
            )
            view.text == TextStage.RUNNING -> Empty(
                Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.summary_text_running_title), stringResource(R.string.summary_text_running_body),
                stringResource(R.string.summary_after_text), Icons.Rounded.AutoAwesome, filled = false, onClick = onSummarizeAfterText,
            )
            view.text == TextStage.NONE && !canConvert -> Empty(
                Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.summary_no_text_title), stringResource(R.string.summary_needs_groq),
                stringResource(R.string.summary_open_labs), Icons.Rounded.Key, filled = false, onClick = onOpenLabs,
            )
            view.text == TextStage.NONE -> Empty(
                Icons.AutoMirrored.Rounded.Notes, stringResource(R.string.summary_no_text_title), stringResource(R.string.summary_no_text_body),
                stringResource(R.string.summary_convert), Icons.Rounded.AutoAwesome, filled = false, onClick = onConvertThenSummarize,
            )
            else -> Empty(
                Icons.Rounded.Summarize, stringResource(R.string.summary_none_title), stringResource(R.string.summary_empty_body),
                stringResource(R.string.summary_action), Icons.Rounded.AutoAwesome, filled = true, onClick = onSummarize,
            )
        }
    }
}

/** 흰 묶음 카드 바탕. 어두울 때는 한 단 밝은 면. */
@Composable
internal fun cardColor(): Color =
    if (AppTheme.colors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainerLowest

/** 시각 알약 바탕. */
@Composable
private fun pillColor(): Color =
    if (AppTheme.colors.isDark) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainer

/** 묶음 안 줄의 모서리: 첫 줄은 위, 끝 줄은 아래만 크게. */
internal fun groupShape(i: Int, n: Int, big: Int = 20, small: Int = 4) = when {
    n == 1 -> RoundedCornerShape(big.dp)
    i == 0 -> RoundedCornerShape(big.dp, big.dp, small.dp, small.dp)
    i == n - 1 -> RoundedCornerShape(small.dp, small.dp, big.dp, big.dp)
    else -> RoundedCornerShape(small.dp)
}

@Composable
private fun Empty(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    actionIcon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = RoundedCornerShape(32.dp), modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp)) }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp).semantics { heading() },
        )
        Text(
            body.keepAll(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, lineBreak = LineBreak.Paragraph),
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            colors = if (filled) ButtonDefaults.buttonColors()
            else ButtonDefaults.buttonColors(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp),
            modifier = Modifier.padding(top = 28.dp).height(56.dp),
        ) {
            Icon(actionIcon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(action, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 기다리는 중·요약하는 중·실패. */
@Composable
private fun Status(s: Summary, textReady: Boolean, onCancel: () -> Unit, onRetry: () -> Unit, onOpenLabs: () -> Unit) {
    val c = MaterialTheme.colorScheme
    if (s.stateEnum == SummaryState.FAILED) {
        Failed(s, onRetry, onOpenLabs)
        return
    }
    val waitingText = s.stateEnum == SummaryState.QUEUED && !textReady
    val title = stringResource(if (waitingText) R.string.summary_state_waiting_text else R.string.summary_state_running)
    val detail = stringResource(if (waitingText) R.string.summary_state_waiting_text_body else R.string.summary_state_batch)
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = cardColor(), shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(strokeWidth = 4.dp, trackColor = c.secondaryContainer, modifier = Modifier.size(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(detail.keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        // 다 되면 들어설 모양을 미리 보여 주는 뼈대.
        Column(Modifier.padding(horizontal = 8.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val bar = c.surfaceContainerHigh
            Box(Modifier.fillMaxWidth(0.58f).height(22.dp).background(bar, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth().height(14.dp).background(bar, RoundedCornerShape(7.dp)))
            Box(Modifier.fillMaxWidth(0.86f).height(14.dp).background(bar, RoundedCornerShape(7.dp)))
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(88.dp).background(c.surfaceContainer, RoundedCornerShape(20.dp)))
            Box(Modifier.fillMaxWidth().height(120.dp).background(c.surfaceContainer, RoundedCornerShape(20.dp)))
        }
    }
}

@Composable
private fun Failed(s: Summary, onRetry: () -> Unit, onOpenLabs: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val detail = stringResource(
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
    )
    val settingsHelp = s.errorEnum == SummaryError.CLAUDE_KEY || s.errorEnum == SummaryError.REFUSED || s.errorEnum == SummaryError.BILLING
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Surface(
            color = c.errorContainer, contentColor = c.onErrorContainer, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.Error, contentDescription = null, tint = c.error)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.summary_state_failed), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(detail.keepAll(), style = MaterialTheme.typography.bodyMedium)
                        if (s.errorDetail.isNotBlank()) {
                            Text(s.errorDetail, style = MaterialTheme.typography.bodySmall, color = c.onErrorContainer.copy(alpha = 0.8f), maxLines = 2)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (settingsHelp) {
                        TextButton(onClick = onOpenLabs) { Text(stringResource(R.string.summary_open_labs), color = c.onErrorContainer) }
                    }
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError)) {
                        Text(stringResource(R.string.ai_action_retry))
                    }
                }
            }
        }
    }
}

/**
 * 다 된 요약: 제목·개요 → 목차 → 공지·강조 → 번호 붙은 주제들 → 용어.
 * 줄 끝의 시각을 누르면 원문으로, 목차 칩을 누르면 그 주제로 간다.
 */
@Composable
private fun Reading(view: SummaryView, onRef: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val s = view.content ?: return
    fun at(refs: List<Int>): Long? = refs.firstOrNull()?.let { view.starts.getOrNull(it) }
    fun go(refs: List<Int>): (() -> Unit)? = refs.firstOrNull()?.let { r -> { onRef(r) } }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showToc = s.sections.size > 1
    // 주제 항목이 몇 번째에 놓이는지: 머리(1) + 목차(0/1) + 공지(0/1).
    val firstSection = 1 + (if (showToc) 1 else 0) + (if (s.notices.isNotEmpty()) 1 else 0)

    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head") {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp)) {
                if (s.title.isNotBlank()) {
                    Text(
                        s.title.keepAll(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, lineBreak = LineBreak.Paragraph),
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (s.overview.isNotBlank()) {
                    Text(
                        s.overview.keepAll(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, lineBreak = LineBreak.Paragraph),
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (showToc) {
            item(key = "toc") {
                Row(
                    Modifier.padding(horizontal = 0.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.sections.forEachIndexed { i, sec ->
                        TocChip(i + 1, sec.heading.ifBlank { "${i + 1}" }) {
                            scope.launch { list.animateScrollToItem(firstSection + i) }
                        }
                    }
                }
            }
        }
        if (s.notices.isNotEmpty()) {
            item(key = "notices") {
                Column {
                    GroupLabel(stringResource(R.string.summary_notices))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        s.notices.forEachIndexed { i, n ->
                            Notice(
                                kind = stringResource(noticeLabel(n.kind)),
                                icon = noticeIcon(n.kind),
                                text = n.text,
                                at = at(n.refs),
                                onClick = go(n.refs),
                                shape = groupShape(i, s.notices.size),
                            )
                        }
                    }
                }
            }
        }
        s.sections.forEachIndexed { i, sec ->
            item(key = "sec$i") {
                Column {
                    SectionHead(i + 1, sec.heading.ifBlank { "${i + 1}" }, at(sec.refs), go(sec.refs))
                    if (sec.points.isNotEmpty()) {
                        Surface(color = cardColor(), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                sec.points.forEach { p -> Point(p.text, at(p.refs), go(p.refs)) }
                            }
                        }
                    }
                }
            }
        }
        if (s.terms.isNotEmpty()) {
            item(key = "terms") {
                Column {
                    GroupLabel(stringResource(R.string.summary_terms))
                    Surface(color = cardColor(), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            s.terms.forEach { t -> Point(t.term, at(t.refs), go(t.refs), detail = t.definition) }
                        }
                    }
                }
            }
        }
    }
}

/** 목차 칩: 번호 배지 + 주제 이름. */
@Composable
private fun TocChip(n: Int, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .height(32.dp)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberBadge(n, size = 20, radius = 6, bg = c.secondaryContainer, fg = c.onSecondaryContainer, fontSize = 11)
                Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun NumberBadge(n: Int, size: Int, radius: Int, bg: Color, fg: Color, fontSize: Int) {
    Box(
        Modifier.size(size.dp).background(bg, RoundedCornerShape(radius.dp)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = fg, style = MaterialTheme.typography.labelSmall.copy(fontSize = fontSize.sp, fontWeight = FontWeight.Bold))
    }
}

/** ‘공지·강조’, ‘용어’ 같은 묶음 이름. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** 공지 한 줄: 분홍 타일 + 종류 · 글 · 시각 알약. */
@Composable
private fun Notice(kind: String, icon: ImageVector, text: String, at: Long?, onClick: (() -> Unit)?, shape: RoundedCornerShape) {
    val c = MaterialTheme.colorScheme
    val click = if (at != null) onClick else null
    val cd = at?.let { stringResource(R.string.summary_cd_ref, "$kind, $text", Timestamps.format(it)) } ?: "$kind, $text"
    Surface(
        onClick = click ?: {},
        enabled = click != null,
        color = cardColor(),
        shape = shape,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = cd },
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = c.tertiaryContainer, contentColor = c.onTertiaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(32.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kind, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = c.onTertiaryContainer)
                Text(text.keepAll(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, lineBreak = LineBreak.Paragraph))
            }
            if (at != null) Box(Modifier.padding(top = 4.dp)) { Pill(at) }
        }
    }
}

/** 주제 머리: 번호 배지 · 제목 · 시각 알약. 누르면 원문으로. */
@Composable
private fun SectionHead(n: Int, heading: String, at: Long?, onClick: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    val click = if (at != null) onClick else null
    val cd = at?.let { stringResource(R.string.summary_cd_ref, heading, Timestamps.format(it)) } ?: heading
    Surface(
        onClick = click ?: {},
        enabled = click != null,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clearAndSetSemantics { contentDescription = cd; heading() },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberBadge(n, size = 24, radius = 8, bg = c.primary, fg = c.onPrimary, fontSize = 12)
            Text(
                heading.keepAll(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Paragraph),
                modifier = Modifier.weight(1f),
            )
            if (at != null) Pill(at)
        }
    }
}

/** 카드 안의 요점(또는 용어) 한 줄: 점 · 글 · 시각. */
@Composable
private fun Point(text: String, at: Long?, onClick: (() -> Unit)?, detail: String? = null) {
    val c = MaterialTheme.colorScheme
    val click = if (at != null) onClick else null
    val body = if (detail != null) "$text, $detail" else text
    val cd = at?.let { stringResource(R.string.summary_cd_ref, body, Timestamps.format(it)) } ?: body
    Surface(
        onClick = click ?: {},
        enabled = click != null,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).clearAndSetSemantics { contentDescription = cd },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail == null) {
                Box(Modifier.padding(top = 8.dp).size(6.dp).background(c.primary, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text.keepAll(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, lineBreak = LineBreak.Paragraph),
                    fontWeight = if (detail != null) FontWeight.SemiBold else null,
                )
                if (detail != null) {
                    Text(
                        detail.keepAll(),
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
                        color = c.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (at != null) {
                Text(
                    Timestamps.format(at),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 원문 시각 알약. */
@Composable
private fun Pill(at: Long) {
    Surface(color = pillColor(), contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(50)) {
        Box(Modifier.height(24.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(Timestamps.format(at), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
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
