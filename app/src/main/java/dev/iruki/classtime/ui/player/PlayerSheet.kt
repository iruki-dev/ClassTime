package dev.iruki.classtime.ui.player

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import dev.iruki.classtime.ai.AiConfig
import dev.iruki.classtime.ai.Paragraph
import dev.iruki.classtime.ui.ai.TranscriptPane
import dev.iruki.classtime.ui.ai.TranscriptView
import dev.iruki.classtime.ui.ai.findHits
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.PlaybackState
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.ui.recordings.SPEEDS
import dev.iruki.classtime.ui.recordings.recordingTitle
import dev.iruki.classtime.ui.recordings.speedLabel
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.util.TimeUtils
import java.time.Instant
import java.time.ZoneId

/**
 * 재생 화면. 구글 녹음기(M3 Expressive)처럼 세 버튼의 높이를 80으로 맞추고, 위계는 너비와 색으로
 * 준다. 재생 버튼은 재생 중엔 모서리가 각지고(28) 멈추면 둥글어진다. 속도는 아래 줄 왼쪽.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(
    recording: Recording,
    icon: String,
    state: PlaybackState,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
    text: PlayerText = PlayerText(),
) {
    val c = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = dragging ?: state.positionMs.toFloat()

    // 실험적 기능이 켜져 있거나 이미 텍스트가 있으면 ‘오디오 | 텍스트’ 탭을 보인다.
    val view = text.view
    val showTabs = text.ai.enabled || view != null
    // 보던 탭은 앱 전체에서 기억한다(닫았다 열어도, 다른 녹음을 열어도 그대로).
    val tab = if (showTabs) text.tab else PlayerTab.AUDIO
    var searching by rememberSaveable(recording.id) { mutableStateOf(false) }
    var query by rememberSaveable(recording.id) { mutableStateOf("") }
    var hitIndex by rememberSaveable(recording.id) { mutableIntStateOf(0) }
    val onText = showTabs && tab == PlayerTab.TEXT
    val reading = view?.done == true
    val shown = view?.paragraphs.orEmpty()
    val hits = remember(shown, query) { findHits(shown, query) }
    val copiedMessage = stringResource(R.string.ai_copied)
    val context = LocalContext.current

    // 아래로 끌어 닫기는 시트를 절반 넘게 내렸을 때만. 그보다 덜 끌고 놓으면 제자리로 돌아온다
    // (기본값은 조금만 끌거나 살짝 튕겨도 닫혀 버려, 끌다가 마음을 바꿀 수 없었다).
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var expandedTop by remember { mutableFloatStateOf(0f) }
    val sheetHolder = remember { arrayOfNulls<SheetState>(1) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value != SheetValue.Hidden) return@rememberModalBottomSheetState true
            val offset = runCatching { sheetHolder[0]?.requireOffset() }.getOrNull() ?: return@rememberModalBottomSheetState true
            val travel = (screenPx - expandedTop).coerceAtLeast(1f)
            val pulled = (offset - expandedTop) / travel
            // 거의 안 끌린 상태 = 뒤로 가기·바깥 누르기로 닫는 것. 그건 그대로 닫는다.
            pulled < 0.02f || pulled >= 0.5f
        },
    )
    sheetHolder[0] = sheetState
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect {
            if (it == SheetValue.Expanded) runCatching { expandedTop = sheetState.requireOffset() }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surfaceContainerLow,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !searching),
    ) {
        // 뒤로: 검색 중이면 검색부터 닫는다(시트는 그다음 뒤로에서 닫힌다).
        BackHandler(enabled = searching) { searching = false; query = "" }
        Column(
            (if (showTabs) Modifier.fillMaxHeight() else Modifier)
                .navigationBarsPadding()
                .padding(bottom = if (onText) 0.dp else 16.dp),
        ) {
            if (onText && searching) {
                SearchBar(
                    query = query,
                    onQuery = { query = it; hitIndex = 0 },
                    count = hits.size,
                    index = hitIndex,
                    onPrev = { if (hits.isNotEmpty()) hitIndex = (hitIndex - 1 + hits.size) % hits.size },
                    onNext = { if (hits.isNotEmpty()) hitIndex = (hitIndex + 1) % hits.size },
                    onClose = { searching = false; query = "" },
                )
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_cd_collapse))
                }
                Text(
                    recording.subject,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (onText && reading) {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.ai_text_search))
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (reading) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_menu_copy)) },
                                onClick = {
                                    menuOpen = false
                                    text.onCopy(shown)
                                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_menu_share)) },
                                onClick = { menuOpen = false; text.onShare(shown) },
                            )
                            if (text.ai.enabled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ai_menu_reconvert)) },
                                    onClick = { menuOpen = false; text.onReconvert() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_menu_delete_text)) },
                                onClick = { menuOpen = false; text.onDeleteText() },
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_menu_folder)) },
                            onClick = { menuOpen = false; onOpenFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }

            if (showTabs) {
                ViewTabs(tab, onPick = { text.onTab(it); if (it == PlayerTab.AUDIO) { searching = false; query = "" } })
            }

            if (onText) {
                TranscriptPane(
                    view = view,
                    canConvert = text.ai.enabled,
                    paused = !text.ai.enabled,
                    durationMs = state.durationMs,
                    positionMs = position.toInt(),
                    query = if (searching) query else "",
                    hitIndex = hitIndex,
                    onSeek = { onSeek(it); if (!state.playing) onToggle() },
                    onConvert = text.onConvert,
                    onCancel = text.onCancel,
                    onRetry = text.onRetry,
                    onOpenLabs = text.onOpenLabs,
                    modifier = Modifier.weight(1f),
                )
                TextDock(
                    state = state,
                    position = position,
                    duration = duration,
                    onDrag = { dragging = it },
                    onDragEnd = { dragging?.let { onSeek(it.toInt()) }; dragging = null },
                    onToggle = onToggle,
                    onSeekBy = onSeekBy,
                    onSpeed = onSpeed,
                )
            } else Column(Modifier.padding(horizontal = 24.dp)) {
                // 시트는 스크롤하지 않으므로, 낮은 화면에서는 위쪽 면만 줄여 조작부가 늘 보이게 한다.
                val heroHeight = (LocalConfiguration.current.screenHeightDp - if (showTabs) 576 else 520).coerceIn(120, 300).dp
                Surface(
                    color = c.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().height(heroHeight),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(color = c.surface, shape = RoundedCornerShape(36.dp), modifier = Modifier.size(120.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    CourseIcons.of(icon, recording.subject).vector,
                                    contentDescription = null,
                                    tint = c.onSurfaceVariant,
                                    modifier = Modifier.size(64.dp),
                                )
                            }
                        }
                    }
                }

                Text(
                    recordingTitle(recording.startedAt),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 28.dp),
                )
                Text(
                    stringResource(
                        if (recording.auto) R.string.player_meta_auto else R.string.player_meta_manual,
                        clock(recording.startedAt),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Slider(
                    value = position.coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.toInt()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.padding(top = 24.dp).semantics {
                        stateDescription = TimeUtils.formatDuration(position.toLong()) + " / " +
                            TimeUtils.formatDuration(state.durationMs.toLong())
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(TimeUtils.formatDuration(position.toLong()), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(TimeUtils.formatDuration(state.durationMs.toLong()), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    GroupButton(Icons.Rounded.Replay10, stringResource(R.string.player_cd_back10), 72.dp, RoundedCornerShape(40.dp), c.secondaryContainer, c.onSecondaryContainer) {
                        onSeekBy(-10_000)
                    }
                    val corner by animateDpAsState(if (state.playing) 28.dp else 40.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "play-corner")
                    GroupButton(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playing) R.string.recordings_cd_pause else R.string.recordings_cd_play),
                        144.dp, RoundedCornerShape(corner), c.primary, c.onPrimary, iconSize = 36.dp, onClick = onToggle,
                    )
                    GroupButton(Icons.Rounded.Forward10, stringResource(R.string.player_cd_forward10), 72.dp, RoundedCornerShape(40.dp), c.secondaryContainer, c.onSecondaryContainer) {
                        onSeekBy(10_000)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.offset(x = (-12).dp)) {
                        val speedCd = stringResource(R.string.player_cd_speed, speedLabel(state.speed))
                        TextButton(onClick = { speedOpen = true }, modifier = Modifier.semantics { contentDescription = speedCd }) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(speedLabel(state.speed))
                        }
                        DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(s)) },
                                    leadingIcon = if (s == state.speed) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                                    } else null,
                                    onClick = { speedOpen = false; onSpeed(s) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onShare, modifier = Modifier.offset(x = 12.dp)) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.recordings_menu_share))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body, recording.fileName)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 플레이어 보기. */
enum class PlayerTab { AUDIO, TEXT }

/** 텍스트 탭에 필요한 것들(실험적 기능). 기능을 쓰지 않으면 기본값 그대로. */
data class PlayerText(
    val ai: AiConfig = AiConfig(),
    val view: TranscriptView? = null,
    /** 지금 보는 탭. 바꾸면 [onTab] 으로 알린다. */
    val tab: PlayerTab = PlayerTab.AUDIO,
    val onTab: (PlayerTab) -> Unit = {},
    val onConvert: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReconvert: () -> Unit = {},
    val onDeleteText: () -> Unit = {},
    val onCopy: (List<Paragraph>) -> Unit = {},
    val onShare: (List<Paragraph>) -> Unit = {},
    val onOpenLabs: () -> Unit = {},
)

/** ‘오디오 | 텍스트’ 연결 버튼(간격 2, 바깥 끝 원, 안쪽 8). */
@Composable
private fun ViewTabs(tab: PlayerTab, onPick: (PlayerTab) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(PlayerTab.AUDIO, Icons.Rounded.GraphicEq, R.string.ai_tab_audio),
            Triple(PlayerTab.TEXT, Icons.AutoMirrored.Rounded.Notes, R.string.ai_tab_text),
        ).forEachIndexed { i, (t, icon, label) ->
            val on = t == tab
            val shape = if (i == 0) RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 8.dp, bottomEnd = 8.dp)
            else RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp)
            Surface(
                shape = shape,
                color = if (on) c.secondaryContainer else Color.Transparent,
                contentColor = if (on) c.onSecondaryContainer else c.onSurface,
                border = if (on) null else BorderStroke(1.dp, c.outline),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .selectable(selected = on, role = Role.Tab) { onPick(t) },
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** 텍스트에서 찾기: 검색어 · 결과 수 · 위아래. */
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    index: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ai_text_search_close))
        }
        val label = stringResource(R.string.ai_text_search)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).focusRequester(focus).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Text(
                if (count == 0) "0" else "${index + 1}/$count",
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        IconButton(onClick = onPrev, enabled = count > 0) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.ai_text_search_prev))
        }
        IconButton(onClick = onNext, enabled = count > 0) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.ai_text_search_next))
        }
    }
}

/** 텍스트를 읽으며 듣는 아래 조작부: 얇은 막대 + [시각] 뒤로 · 재생 · 앞으로 [속도]. */
@Composable
private fun TextDock(
    state: PlaybackState,
    position: Float,
    duration: Int,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggle: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var speedOpen by remember { mutableStateOf(false) }
    Surface(color = c.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            Slider(
                value = position.coerceIn(0f, duration.toFloat()),
                onValueChange = onDrag,
                onValueChangeFinished = onDragEnd,
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.semantics {
                    stateDescription = TimeUtils.formatDuration(position.toLong()) + " / " + TimeUtils.formatDuration(state.durationMs.toLong())
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TimeUtils.formatDuration(position.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onSeekBy(-10_000) }) {
                    Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.player_cd_back10))
                }
                Spacer(Modifier.width(12.dp))
                val corner by animateDpAsState(if (state.playing) 16.dp else 28.dp, spring(dampingRatio = 0.6f, stiffness = 800f), label = "dock-play")
                Surface(
                    onClick = onToggle,
                    shape = RoundedCornerShape(corner),
                    color = c.primary,
                    contentColor = c.onPrimary,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.playing) R.string.recordings_cd_pause else R.string.recordings_cd_play),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = { onSeekBy(10_000) }) {
                    Icon(Icons.Rounded.Forward10, contentDescription = stringResource(R.string.player_cd_forward10))
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                    val speedCd = stringResource(R.string.player_cd_speed, speedLabel(state.speed))
                    TextButton(onClick = { speedOpen = true }, modifier = Modifier.semantics { contentDescription = speedCd }) {
                        Text(speedLabel(state.speed))
                    }
                    DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                        SPEEDS.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(speedLabel(s)) },
                                leadingIcon = if (s == state.speed) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else null,
                                onClick = { speedOpen = false; onSpeed(s) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 재생 버튼 그룹의 한 칸. 셋 다 높이 80. */
@Composable
private fun GroupButton(
    icon: ImageVector,
    label: String,
    width: Dp,
    shape: RoundedCornerShape,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    iconSize: Dp = 32.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = content,
        modifier = Modifier.width(width).height(80.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
    }
}

private fun clock(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return TimeUtils.minuteToText(t.hour * 60 + t.minute)
}
