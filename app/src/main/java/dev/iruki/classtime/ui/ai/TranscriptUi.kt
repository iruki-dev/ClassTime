package dev.iruki.classtime.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.Paragraph
import dev.iruki.classtime.ai.Paragraphs
import dev.iruki.classtime.ai.Timestamps
import dev.iruki.classtime.ai.TranscriptJson
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptError
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.ui.common.clockLabel
import dev.iruki.classtime.ui.common.formatSpan
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.SystemScreens
import java.time.Instant
import java.time.ZoneId

/**
 * 플레이어 텍스트 탭이 그리는 것. 디코딩은 ViewModel 이 미리 해 둔다.
 *
 * @param ahead 대기열에서 이 작업보다 앞에 있는 작업 수.
 */
data class TranscriptView(
    val transcript: Transcript,
    val paragraphs: List<Paragraph>,
    val ahead: Int,
) {
    val done get() = transcript.stateEnum == TranscriptState.DONE

    companion object {
        fun of(t: Transcript, ahead: Int): TranscriptView {
            // 끝난 작업은 저장된 문단을, 진행 중이면 받아 적은 데까지를 문단으로 묶어 보여 준다.
            val paragraphs = TranscriptJson.paragraphs(t.paragraphs).takeIf { it.isNotEmpty() && t.stateEnum == TranscriptState.DONE }
                ?: Paragraphs.fromSegments(TranscriptJson.segments(t.segments))
            return TranscriptView(t, paragraphs, ahead)
        }
    }
}

/** 진행률 0..1. 단계에 진행 개념이 없으면 null(대기·나누기). */
internal fun progressOf(t: Transcript): Float? = when (t.stateEnum) {
    TranscriptState.TRANSCRIBING, TranscriptState.CORRECTING ->
        TranscriptJson.plan(t.plan).size.takeIf { it > 0 }?.let { t.chunksDone.toFloat() / it }
    else -> null
}

/** “3/8” 같은 걸음 수. 없으면 빈 문자열. */
internal fun stepCount(t: Transcript): String = when (t.stateEnum) {
    TranscriptState.TRANSCRIBING, TranscriptState.CORRECTING ->
        TranscriptJson.plan(t.plan).size.takeIf { it > 0 }?.let { "${t.chunksDone}/$it" }.orEmpty()
    else -> ""
}

/** 단계 이름(+ 걸음 수). */
@Composable
internal fun stageLabel(t: Transcript): String {
    val stage = stringResource(
        when (t.stateEnum) {
            TranscriptState.QUEUED -> R.string.ai_state_queued
            TranscriptState.PREPARING -> R.string.ai_state_preparing
            else -> R.string.ai_state_transcribing
        }
    )
    val count = stepCount(t)
    return if (count.isEmpty()) stage else "$stage · $count"
}

/** epoch ms → “오후 2:20”. */
@Composable
internal fun clockText(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return clockLabel(t.hour * 60 + t.minute)
}

/**
 * 시트 안의 목록이 맨 위에 닿은 뒤 남는 스크롤을 여기서 먹어 버린다. 그래야 글을 내리다가
 * 손가락이 이어서 아래로 끌려도 시트가 따라 내려가 닫히지 않는다. 시트는 손잡이·머리로만 끈다.
 */
internal val KeepScrollInside = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

// --- 녹음 목록의 상태 표시 ---

/**
 * 녹음 줄 보조 글 끝에 붙는 상태. 없음 → 아무것도. 대기 → 시계, 진행 → 작은 원 + 3/8,
 * 끝남 → 글 아이콘, 실패 → 빨간 오류. 낭독기는 이름을 읽는다.
 */
@Composable
fun TranscriptBadge(t: Transcript?) {
    if (t == null) return
    val c = MaterialTheme.colorScheme
    val state = t.stateEnum
    val label = stringResource(
        when (state) {
            TranscriptState.DONE -> R.string.ai_status_done
            TranscriptState.FAILED -> R.string.ai_status_failed
            TranscriptState.QUEUED -> R.string.ai_status_queued
            else -> R.string.ai_status_working
        }
    )
    val working = state.active && state != TranscriptState.QUEUED
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("·", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        when {
            working -> {
                val p = progressOf(t)
                if (p != null) {
                    CircularProgressIndicator(progress = { p }, modifier = Modifier.size(14.dp), strokeWidth = 2.dp, trackColor = c.secondaryContainer)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, trackColor = c.secondaryContainer)
                }
                val count = stepCount(t)
                if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.bodyMedium, color = c.primary)
            }
            state == TranscriptState.QUEUED -> Icon(Icons.Rounded.Schedule, null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
            state == TranscriptState.DONE -> Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
            else -> Icon(Icons.Rounded.Error, null, tint = c.error, modifier = Modifier.size(16.dp))
        }
    }
}

// --- 플레이어 텍스트 탭 ---

/** 검색 결과 하나: 몇 번째 문단의 몇 번째 글자. */
data class Hit(val paragraph: Int, val start: Int)

fun findHits(paragraphs: List<Paragraph>, query: String): List<Hit> {
    if (query.isBlank()) return emptyList()
    val out = mutableListOf<Hit>()
    paragraphs.forEachIndexed { i, p ->
        var from = 0
        while (true) {
            val at = p.text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += Hit(i, at)
            from = at + query.length
        }
    }
    return out
}

/**
 * 텍스트 탭 본문. 상태에 따라 빈 화면 · 진행 카드(+받아 적은 부분) · 대본을 그린다.
 *
 * 대본은 재생 위치의 문단을 보조 컨테이너로 칠하고 그 문단을 따라 스크롤한다. 사용자가 직접
 * 스크롤하면 따라가기를 멈추고 ‘지금 위치로’ 버튼을 띄운다. 문단을 누르면 그 자리부터 재생.
 */
@Composable
fun TranscriptPane(
    view: TranscriptView?,
    canConvert: Boolean,
    paused: Boolean,
    durationMs: Int,
    positionMs: Int,
    query: String,
    hitIndex: Int,
    onSeek: (Int) -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenLabs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().nestedScroll(KeepScrollInside)) {
        when {
            view == null -> EmptyText(durationMs, canConvert, onConvert)
            view.done -> Reading(view, positionMs, query, hitIndex, onSeek)
            else -> Progress(view, paused, onCancel, onRetry, onOpenLabs)
        }
    }
}

@Composable
private fun EmptyText(durationMs: Int, canConvert: Boolean, onConvert: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = c.surfaceContainerHigh, shape = RoundedCornerShape(32.dp), modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(48.dp))
            }
        }
        Text(stringResource(R.string.ai_text_none_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp))
        if (durationMs > 0) {
            Text(
                formatSpan((durationMs + 59_999) / 60_000),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (canConvert) {
            Button(onClick = onConvert, modifier = Modifier.padding(top = 24.dp).height(56.dp)) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_text_convert), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 진행 카드 + 아래에 이미 받아 적은 부분 미리 보기. */
@Composable
private fun Progress(view: TranscriptView, paused: Boolean, onCancel: () -> Unit, onRetry: () -> Unit, onOpenLabs: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val t = view.transcript
    val now = System.currentTimeMillis()
    val failed = t.stateEnum == TranscriptState.FAILED
    val quota = !failed && t.waitUntil > now && t.errorEnum == TranscriptError.QUOTA
    val retrying = !failed && t.waitUntil > now && t.errorEnum == TranscriptError.SERVER
    val offline = !failed && t.errorEnum == TranscriptError.NETWORK
    val needsAccess = failed && t.errorEnum == TranscriptError.PERMISSION

    // 들여온 파일을 읽을 권한: 허용하면 바로 다시 시도, 다시 묻지 않음 상태면 앱 정보 화면으로.
    val access = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRetry() else SystemScreens.openAppDetails(context)
    }

    data class Look(val bg: Color, val fg: Color, val iconFg: Color, val subFg: Color, val icon: ImageVector)
    val look = when {
        failed -> Look(c.errorContainer, c.onErrorContainer, c.error, c.onErrorContainer, Icons.Rounded.Error)
        quota -> Look(AppTheme.colors.cautionContainer, AppTheme.colors.onCautionContainer, AppTheme.colors.caution, AppTheme.colors.onCautionContainer, Icons.Rounded.HourglassTop)
        offline -> Look(c.surfaceContainerHigh, c.onSurface, c.onSurfaceVariant, c.onSurfaceVariant, Icons.Rounded.WifiOff)
        t.stateEnum == TranscriptState.QUEUED -> Look(c.surfaceContainerHigh, c.onSurface, c.onSurfaceVariant, c.onSurfaceVariant, Icons.Rounded.Schedule)
        else -> Look(c.surfaceContainerHigh, c.onSurface, c.primary, c.onSurfaceVariant, Icons.Rounded.GraphicEq)
    }
    val title = when {
        failed -> stringResource(R.string.ai_state_failed)
        quota -> stringResource(R.string.ai_state_quota)
        else -> stringResource(
            when (t.stateEnum) {
                TranscriptState.QUEUED -> R.string.ai_state_queued
                TranscriptState.PREPARING -> R.string.ai_state_preparing
                else -> R.string.ai_state_transcribing
            }
        )
    }
    val count = when {
        failed -> ""
        t.stateEnum == TranscriptState.QUEUED -> if (view.ahead > 0) stringResource(R.string.ai_state_queued_ahead, view.ahead) else ""
        else -> stepCount(t)
    }
    val detail: String? = when {
        failed -> stringResource(
            when (t.errorEnum) {
                TranscriptError.GROQ_KEY -> R.string.ai_fail_key
                TranscriptError.PERMISSION -> R.string.ai_fail_permission
                TranscriptError.FILE -> R.string.ai_fail_file
                TranscriptError.FORMAT -> R.string.ai_fail_format
                TranscriptError.NO_SPEECH -> R.string.ai_fail_no_speech
                TranscriptError.SERVER -> R.string.ai_fail_server
                else -> R.string.ai_fail_unknown
            }
        )
        paused -> stringResource(R.string.ai_state_paused)
        quota -> stringResource(R.string.ai_state_quota_detail, clockText(t.waitUntil))
        retrying -> stringResource(R.string.ai_state_retry_at, clockText(t.waitUntil))
        offline -> stringResource(R.string.ai_state_offline)
        else -> null
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
        item(key = "card") {
            Surface(color = look.bg, contentColor = look.fg, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(look.icon, contentDescription = null, tint = look.iconFg)
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.bodyMedium, color = look.subFg)
                    }
                    val p = progressOf(t)
                    if (!failed && !quota && p != null) {
                        LinearProgressIndicator(progress = { p }, strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth().padding(start = 36.dp))
                    }
                    if (!failed && !quota && t.stateEnum != TranscriptState.QUEUED) {
                        Steps(t, Modifier.padding(start = 36.dp))
                    }
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = look.subFg, modifier = Modifier.padding(start = 36.dp))
                    }
                    // 원인을 찾을 수 있게 실패의 기술적 원인 한 줄(작게).
                    if (failed && t.errorDetail.isNotBlank()) {
                        Text(
                            t.errorDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = look.subFg.copy(alpha = 0.8f),
                            maxLines = 2,
                            modifier = Modifier.padding(start = 36.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (failed) {
                            when {
                                t.errorEnum == TranscriptError.GROQ_KEY ->
                                    TextButton(onClick = onOpenLabs) { Text(stringResource(R.string.ai_action_check_key), color = look.subFg) }
                                needsAccess ->
                                    TextButton(onClick = { access.launch(AppPermissions.audioReadPermission()) }) {
                                        Text(stringResource(R.string.ai_action_allow_access), color = look.subFg)
                                    }
                            }
                            Button(onClick = onRetry) { Text(stringResource(R.string.ai_action_retry)) }
                        } else {
                            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = if (quota) look.subFg else c.primary) }
                        }
                    }
                }
            }
        }
        if (view.paragraphs.isNotEmpty()) {
            item(key = "partial_h") {
                Text(
                    stringResource(R.string.ai_text_partial),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(view.paragraphs, key = { i, _ -> "p$i" }) { _, p ->
                Text(
                    buildAnnotatedString {
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.outline))
                        append(Timestamps.format(p.startMs))
                        pop()
                        append("  ")
                        append(p.text)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph),
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 나누기 → 받아 적기 두 단계. */
@Composable
private fun Steps(t: Transcript, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val now = when (t.stateEnum) {
        TranscriptState.QUEUED, TranscriptState.PREPARING -> 0
        else -> 1
    }
    val labels = listOf(R.string.ai_step_split, R.string.ai_step_transcribe)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        labels.forEachIndexed { i, label ->
            val icon = when {
                i < now -> Icons.Rounded.CheckCircle
                i == now -> Icons.Rounded.RadioButtonChecked
                else -> Icons.Rounded.RadioButtonUnchecked
            }
            val color = if (i <= now) c.primary else c.outline
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

/** 다 된 대본. */
@Composable
private fun Reading(
    view: TranscriptView,
    positionMs: Int,
    query: String,
    hitIndex: Int,
    onSeek: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val paragraphs = view.paragraphs
    val current = paragraphs.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val hits = remember(paragraphs, query) { findHits(paragraphs, query) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    var following by rememberSaveable { mutableStateOf(true) }

    // 손으로 끌면 따라가기를 멈춘다(프로그램 스크롤은 끌기가 아니다).
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    // 지금 문단의 바로 앞 문단이 맨 위에 오게: 앞 문맥을 보며 읽을 수 있다.
    LaunchedEffect(current, following, query.isBlank()) {
        if (following && query.isBlank()) list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LaunchedEffect(hitIndex, hits) {
        hits.getOrNull(hitIndex)?.let { list.animateScrollToItem((it.paragraph - 1).coerceAtLeast(0)) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp)) {
            itemsIndexed(paragraphs, key = { i, p -> "${p.startMs}_$i" }) { i, p ->
                val on = i == current && query.isBlank()
                val stamp = Timestamps.format(p.startMs)
                val seekCd = stringResource(R.string.ai_text_seek, stamp)
                Surface(
                    onClick = { following = true; onSeek(p.startMs.toInt()) },
                    color = if (on) c.secondaryContainer else Color.Transparent,
                    contentColor = if (on) c.onSecondaryContainer else c.onSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { contentDescription = seekCd },
                ) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
                            if (on) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                stamp,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (on) c.onSecondaryContainer else c.primary,
                            )
                        }
                        Text(
                            highlighted(p.text, i, hits, hitIndex, query.length),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp, lineBreak = LineBreak.Paragraph),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !following && query.isBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = { following = true },
                icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                text = { Text(stringResource(R.string.ai_text_follow)) },
                containerColor = c.primaryContainer,
                contentColor = c.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun highlighted(text: String, paragraph: Int, hits: List<Hit>, current: Int, length: Int): AnnotatedString {
    val mine = hits.withIndex().filter { it.value.paragraph == paragraph }
    if (mine.isEmpty() || length == 0) return AnnotatedString(text)
    val colors = AppTheme.colors
    return buildAnnotatedString {
        append(text)
        mine.forEach { (index, hit) ->
            val on = index == current
            addStyle(
                SpanStyle(
                    background = if (on) colors.caution.copy(alpha = 0.55f) else colors.cautionContainer,
                    color = colors.onCautionContainer,
                    fontWeight = if (on) FontWeight.SemiBold else null,
                ),
                hit.start,
                (hit.start + length).coerceAtMost(text.length),
            )
        }
    }
}
