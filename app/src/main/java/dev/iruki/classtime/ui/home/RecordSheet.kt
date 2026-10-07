package dev.iruki.classtime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.ui.common.CourseIconTile
import dev.iruki.classtime.ui.common.tileContainer
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.IconTile
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.ui.theme.CourseIcons
import dev.iruki.classtime.ui.theme.GroupShapes

/**
 * 시간표에 없는 시간에 녹음할 때 과목을 고르는 시트.
 * 시간표의 과목을 바로 고를 수 있어 대부분 타이핑이 필요 없다. [onStart] 에 null 을 주면 ‘기타’.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSheet(
    subjects: List<SubjectOption>,
    onDismiss: () -> Unit,
    onStart: (String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 0..n-1 은 과목, n 은 ‘직접 입력’.
    val customIndex = subjects.size
    var selected by remember { mutableIntStateOf(if (subjects.isEmpty()) customIndex else 0) }
    var custom by remember { mutableStateOf("") }
    val chosenName = if (selected < customIndex) subjects[selected].subject else custom.trim().ifBlank { null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.record_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                stringResource(R.string.record_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 16.dp),
            )

            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(GroupGap)) {
                val count = subjects.size + 1
                subjects.forEachIndexed { i, option ->
                    ChoiceRow(
                        index = i, count = count,
                        selected = selected == i,
                        onSelect = { selected = i },
                        leading = {
                            CourseIconTile(
                                CourseIcons.of(option.icon, option.subject),
                                container = if (selected == i) AppTheme.colors.group else tileContainer(),
                            )
                        },
                        label = option.subject,
                    )
                }
                ChoiceRow(
                    index = customIndex, count = count,
                    selected = selected == customIndex,
                    onSelect = { selected = customIndex },
                    leading = {
                        IconTile(
                            Icons.Rounded.Edit,
                            if (selected == customIndex) AppTheme.colors.group else tileContainer(),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    label = stringResource(R.string.record_sheet_custom),
                )
            }
            if (selected == customIndex) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    label = { Text(stringResource(R.string.record_sheet_custom_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onStart(chosenName) },
                enabled = chosenName != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (chosenName != null) stringResource(R.string.record_sheet_start, chosenName)
                    else stringResource(R.string.record_sheet_start_plain),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(
                onClick = { onStart(null) },
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.record_sheet_unnamed, stringResource(R.string.subject_unknown)))
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    index: Int,
    count: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    leading: @Composable () -> Unit,
    label: String,
) {
    GroupRow(
        index = index,
        count = count,
        selected = selected,
        color = if (selected) null else AppTheme.colors.group,
        minHeight = 56.dp,
        modifier = Modifier
            .clip(GroupShapes.at(index, count))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        leading = leading,
        trailing = { RadioButton(selected = selected, onClick = null, modifier = Modifier.size(24.dp)) },
    ) { RowHeadline(label) }
}
