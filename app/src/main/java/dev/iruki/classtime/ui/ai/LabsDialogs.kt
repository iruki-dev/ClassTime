package dev.iruki.classtime.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.ai.BulkPlan
import dev.iruki.classtime.ai.SummaryModel
import dev.iruki.classtime.ui.common.formatSpan
import dev.iruki.classtime.ui.common.keepAll

/**
 * ‘전체 텍스트 변환·요약’ 확인 창. 무엇을 몇 건 맡기는지, 녹음이 얼마나 되는지,
 * 요약 요금이 대략 얼마인지 보여 준다.
 */
@Composable
fun BulkDialog(
    plan: BulkPlan,
    summarize: Boolean,
    model: SummaryModel,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val rows = buildList {
        if (plan.transcribe.isNotEmpty()) {
            add(
                BulkLine(
                    icon = if (summarize) Icons.Rounded.AutoAwesome else Icons.Rounded.Notes,
                    title = stringResource(if (summarize) R.string.bulk_row_text_summary else R.string.bulk_row_text),
                    detail = stringResource(R.string.bulk_row_length, formatSpan((plan.transcribeMs / 60_000).toInt())),
                    count = plan.transcribe.size,
                )
            )
        }
        if (plan.summarize.isNotEmpty()) {
            add(BulkLine(Icons.Rounded.Summarize, stringResource(R.string.bulk_row_summary), null, plan.summarize.size))
        }
    }
    val note = buildList {
        if (summarize) add(stringResource(R.string.bulk_cost, "%.2f".format(plan.estimate(model, summarizeTranscribed = true))))
        if (plan.transcribe.isNotEmpty()) add(stringResource(R.string.bulk_quota_note))
    }.joinToString(" ")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceContainerLow,
        icon = { Icon(Icons.Rounded.DoneAll, contentDescription = null, tint = c.primary) },
        title = { Text(stringResource(if (summarize) R.string.bulk_title_summary else R.string.bulk_title_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    rows.forEachIndexed { i, r -> BulkRow(r, groupShape(i, rows.size, big = 16)) }
                }
                if (note.isNotEmpty()) {
                    Text(note.keepAll(), style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Paragraph), color = c.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = onStart, contentPadding = PaddingValues(horizontal = 24.dp)) { Text(stringResource(R.string.bulk_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private data class BulkLine(val icon: ImageVector, val title: String, val detail: String?, val count: Int)

@Composable
private fun BulkRow(r: BulkLine, shape: RoundedCornerShape) {
    val c = MaterialTheme.colorScheme
    val countText = stringResource(R.string.bulk_count, r.count)
    Surface(
        color = cardColor(), shape = shape,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = listOfNotNull(r.title, r.detail, countText).joinToString(", ")
        },
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(r.icon, contentDescription = null, tint = c.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.bodyLarge, color = c.onSurface)
                if (r.detail != null) Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Text(countText, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = c.onSurface)
        }
    }
}

/**
 * Google 동의 화면 전에 띄우는 안내 시트. 드라이브에 무엇이 어떻게 생기는지를 먼저 보여 준다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveConnectSheet(onConnect: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AddToDrive, contentDescription = null) }
                }
                Column {
                    Text(stringResource(R.string.drive_connect), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium))
                    Text(stringResource(R.string.drive_sheet_sub), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }

            // 드라이브에 생기는 모양.
            Surface(
                color = cardColor(), shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).semantics(mergeDescendants = true) {},
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TreeLine(0, Icons.Rounded.Folder, stringResource(R.string.drive_tree_root), muted = true)
                    TreeLine(1, Icons.Rounded.Folder, stringResource(R.string.drive_tree_subject))
                    TreeLine(2, Icons.Rounded.Description, stringResource(R.string.drive_tree_text), accent = true)
                    TreeLine(2, Icons.Rounded.Summarize, stringResource(R.string.drive_tree_summary), accent = true)
                    TreeLine(2, Icons.Rounded.MusicOff, stringResource(R.string.drive_tree_audio), muted = true, struck = true)
                }
            }

            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Bullet(Icons.Rounded.Sync, stringResource(R.string.drive_point_sync))
                Bullet(Icons.Rounded.Delete, stringResource(R.string.drive_point_trash))
                Bullet(Icons.Rounded.Lock, stringResource(R.string.drive_point_scope))
            }

            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(56.dp),
            ) { Text(stringResource(R.string.drive_connect_google), style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
                Text(stringResource(R.string.drive_later))
            }
        }
    }
}

@Composable
private fun TreeLine(depth: Int, icon: ImageVector, text: String, accent: Boolean = false, muted: Boolean = false, struck: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(start = (28 * depth).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = if (accent) c.primary else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
            color = if (muted) c.onSurfaceVariant else c.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text.keepAll(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
