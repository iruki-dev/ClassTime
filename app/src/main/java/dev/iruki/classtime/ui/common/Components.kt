package dev.iruki.classtime.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcon
import dev.iruki.classtime.ui.theme.GroupShapes

/** 화면 좌우 여백. Compact 창 기준(M3). */
val ScreenPadding = 16.dp

/**
 * 탭 화면 머리(M3 Large flexible app bar 와 같은 구성): 오른쪽 액션 줄 + 큰 제목 + 부제.
 * 목록의 첫 항목으로 넣어 스크롤과 함께 올라가게 한다.
 */
@Composable
fun LargeHeader(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(start = ScreenPadding, end = 4.dp, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 상세·편집 화면 앱 바. [close] 면 닫기(X), 아니면 뒤로. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onNavigate: () -> Unit,
    close: Boolean = false,
    containerColor: Color = Color.Transparent,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onNavigate) {
                Icon(
                    if (close) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(if (close) R.string.action_close else R.string.action_back),
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
        ),
    )
}

/** 묶음 위의 섹션 제목 줄(높이 48). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/**
 * M3 Expressive 분리형 목록의 한 줄. 같은 묶음의 줄끼리 2dp 간격으로 쌓고,
 * [index]/[count] 로 바깥 모서리(20)와 안쪽 모서리(4)를 정한다.
 */
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color? = null,
    minHeight: Dp = 72.dp,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    val container = color ?: if (selected) MaterialTheme.colorScheme.secondaryContainer else AppTheme.colors.group
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .padding(start = 16.dp, end = if (trailing != null) 12.dp else 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                headline()
                supporting?.invoke()
            }
            trailing?.invoke()
        }
    }
    val shape = GroupShapes.at(index, count)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        Surface(shape = shape, color = container, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/** 묶음 안 줄 사이 간격. */
val GroupGap = 2.dp

@Composable
fun RowHeadline(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun RowSupporting(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** 아이콘 타일 바탕. 흰 묶음 위에서 살짝 들어가 보이는 중립 면. */
@Composable
fun tileContainer(): Color =
    if (AppTheme.colors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surfaceContainer

/**
 * 과목 아이콘 타일. **무채색**이다 — 과목 색은 시간표 칸에만 쓰고, 아이콘과 같은 요소에
 * 섞지 않는다. 줄마다 색이 바뀌면 녹음·다음 같은 상태 색이 묻힌다.
 */
@Composable
fun CourseIconTile(
    icon: CourseIcon,
    size: Dp = 40.dp,
    container: Color = tileContainer(),
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        Modifier.size(size).background(container, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon.vector, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}

/** 아이콘 타일(아바타 자리에 아이콘). */
@Composable
fun IconTile(icon: ImageVector, container: Color, content: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content)
    }
}

/** 켜짐에 체크 아이콘을 넣은 M3 스위치. */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
    )
}

/** 줄 끝에 붙는 작은 상태 표시(아이콘 + 라벨). */
@Composable
fun StatusLabel(
    text: String,
    icon: ImageVector?,
    color: Color,
    container: Color = Color.Transparent,
) {
    Row(
        Modifier
            .background(container, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = if (container == Color.Transparent) 0.dp else 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
