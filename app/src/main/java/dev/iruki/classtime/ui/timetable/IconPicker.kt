package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcon
import dev.iruki.classtime.ui.theme.CourseIcons

/** 과목명 옆의 아이콘 타일. 노션 페이지 아이콘처럼 눌러서 바꾼다. */
@Composable
internal fun IconPickerButton(icon: CourseIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val label = stringResource(R.string.course_icon_cd)
    Box(modifier.size(56.dp)) {
        Surface(
            onClick = onClick,
            color = c.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(56.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon.vector, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(22.dp)
                .border(2.dp, c.surface, CircleShape)
                .padding(2.dp)
                .clip(CircleShape),
        ) {
            Surface(color = c.primary, contentColor = c.onPrimary, shape = CircleShape, modifier = Modifier.size(18.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** 아이콘 30개 중에서 고르는 시트. 누르면 바로 바뀌고 닫힌다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconPickerSheet(
    subject: String,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val guessed = CourseIcons.guess(subject)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.course_icon_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // 빈 값으로 돌리면 다시 이름을 따라간다.
                TextButton(onClick = { onPick("") }) { Text(stringResource(R.string.course_icon_auto)) }
            }
            Text(
                if (subject.isBlank()) stringResource(R.string.course_icon_hint_blank)
                else stringResource(R.string.course_icon_hint, subject.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 16.dp),
            )
            Surface(color = AppTheme.colors.group, shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.padding(12.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CourseIcons.all.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { icon ->
                                val on = icon.key == selected
                                val label = iconLabel(icon.key)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .clip(MaterialTheme.shapes.large)
                                        .then(if (on) Modifier.border(2.dp, c.primary, MaterialTheme.shapes.large) else Modifier)
                                        .selectable(selected = on, role = Role.RadioButton) { onPick(icon.key) }
                                        .semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (on) {
                                        CourseIconTile(icon, size = 52.dp, container = c.primaryContainer, content = c.onPrimaryContainer)
                                    } else {
                                        Icon(
                                            icon.vector,
                                            contentDescription = null,
                                            tint = if (icon == guessed) c.primary else c.onSurfaceVariant,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 화면 낭독기가 읽을 아이콘 이름. */
@Composable
private fun iconLabel(key: String): String = stringResource(
    when (key) {
        "book" -> R.string.icon_book
        "school" -> R.string.icon_school
        "lightbulb" -> R.string.icon_lightbulb
        "functions" -> R.string.icon_functions
        "calculate" -> R.string.icon_calculate
        "stats" -> R.string.icon_stats
        "code" -> R.string.icon_code
        "computer" -> R.string.icon_computer
        "memory" -> R.string.icon_memory
        "science" -> R.string.icon_science
        "biotech" -> R.string.icon_biotech
        "eco" -> R.string.icon_eco
        "medical" -> R.string.icon_medical
        "psychology" -> R.string.icon_psychology
        "translate" -> R.string.icon_translate
        "history" -> R.string.icon_history
        "forum" -> R.string.icon_forum
        "public" -> R.string.icon_public
        "gavel" -> R.string.icon_gavel
        "bank" -> R.string.icon_bank
        "business" -> R.string.icon_business
        "engineering" -> R.string.icon_engineering
        "architecture" -> R.string.icon_architecture
        "rocket" -> R.string.icon_rocket
        "palette" -> R.string.icon_palette
        "draw" -> R.string.icon_draw
        "camera" -> R.string.icon_camera
        "theater" -> R.string.icon_theater
        "music" -> R.string.icon_music
        else -> R.string.icon_sports
    }
)
