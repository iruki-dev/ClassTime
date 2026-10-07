package dev.iruki.classtime.ui.timetable

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.theme.CourseIcon
import dev.iruki.classtime.ui.theme.CourseIcons

/** 과목명 옆 56 아이콘 타일. 노션 페이지 아이콘처럼 눌러서 바꾼다. */
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
        Surface(
            color = c.primary,
            contentColor = c.onPrimary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .size(22.dp)
                .border(2.dp, c.surface, CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/**
 * 아이콘 고르기. 검색 + 첫 칸 ‘자동’(과목명으로 짐작) + 40개, 48 격자 6열.
 * 누르면 바로 바뀌고 닫힌다. 색은 넣지 않는다.
 *
 * @param selected 저장된 키. 빈 값이면 ‘자동’.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconPickerSheet(
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val labels = CourseIcons.all.associate { it.key to iconLabel(it.key) }
    val results = CourseIcons.search(query) { labels[it.key].orEmpty() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.course_icon_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.course_icon_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceContainerHigh,
                    unfocusedContainerColor = c.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (results.isEmpty()) {
                Text(
                    stringResource(R.string.course_icon_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(440.dp),
            ) {
                if (query.isBlank()) {
                    item(key = "auto") {
                        IconCell(
                            vector = Icons.Rounded.AutoAwesome,
                            label = stringResource(R.string.course_icon_auto),
                            selected = selected.isBlank(),
                            neutralFill = true,
                        ) { onPick("") }
                    }
                }
                items(results, key = { it.key }) { icon ->
                    IconCell(
                        vector = icon.vector,
                        label = labels[icon.key].orEmpty(),
                        selected = icon.key == selected,
                        neutralFill = false,
                    ) { onPick(icon.key) }
                }
            }
        }
    }
}

/** 48 칸. 고른 것은 primaryContainer 로 채우고 모서리를 16으로 각지게(M3E 토글 모양). */
@Composable
private fun IconCell(
    vector: ImageVector,
    label: String,
    selected: Boolean,
    neutralFill: Boolean,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = if (selected) MaterialTheme.shapes.large else CircleShape,
            color = when {
                selected -> c.primaryContainer
                neutralFill -> c.surfaceContainerHigh
                else -> Color.Transparent
            },
            contentColor = if (selected) c.onPrimaryContainer else c.onSurfaceVariant,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = label
                role = Role.RadioButton
                this.selected = selected
            },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(vector, contentDescription = null) }
        }
    }
}

/** 화면 낭독기와 검색이 쓰는 아이콘 이름. */
@Composable
private fun iconLabel(key: String): String = stringResource(
    when (key) {
        "book" -> R.string.icon_book
        "school" -> R.string.icon_school
        "lightbulb" -> R.string.icon_lightbulb
        "functions" -> R.string.icon_functions
        "calculate" -> R.string.icon_calculate
        "stats" -> R.string.icon_stats
        "savings" -> R.string.icon_savings
        "code" -> R.string.icon_code
        "computer" -> R.string.icon_computer
        "memory" -> R.string.icon_memory
        "electric" -> R.string.icon_electric
        "science" -> R.string.icon_science
        "biotech" -> R.string.icon_biotech
        "eco" -> R.string.icon_eco
        "agriculture" -> R.string.icon_agriculture
        "pets" -> R.string.icon_pets
        "medical" -> R.string.icon_medical
        "psychology" -> R.string.icon_psychology
        "childcare" -> R.string.icon_childcare
        "groups" -> R.string.icon_groups
        "translate" -> R.string.icon_translate
        "history" -> R.string.icon_history
        "forum" -> R.string.icon_forum
        "news" -> R.string.icon_news
        "public" -> R.string.icon_public
        "gavel" -> R.string.icon_gavel
        "bank" -> R.string.icon_bank
        "business" -> R.string.icon_business
        "engineering" -> R.string.icon_engineering
        "construction" -> R.string.icon_construction
        "architecture" -> R.string.icon_architecture
        "rocket" -> R.string.icon_rocket
        "palette" -> R.string.icon_palette
        "draw" -> R.string.icon_draw
        "camera" -> R.string.icon_camera
        "theater" -> R.string.icon_theater
        "music" -> R.string.icon_music
        "piano" -> R.string.icon_piano
        "food" -> R.string.icon_food
        else -> R.string.icon_sports
    }
)
