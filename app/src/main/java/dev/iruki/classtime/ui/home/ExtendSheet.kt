package dev.iruki.classtime.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.util.TimeUtils

/**
 * 수업 연장. 고르면 바로 적용하고 닫는다 — 확인 단계를 하나 더 두면 수업이 끝나는 그 순간에
 * 놓친다. 각 칸에 새로 멈출 시각을 같이 적어 ‘몇 분’을 계산하지 않아도 되게 했다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtendSheet(
    plannedEndAt: Long,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val base = maxOf(plannedEndAt, System.currentTimeMillis())
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.extend_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                stringResource(R.string.extend_body),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordingService.EXTEND_CHOICES.forEach { minutes ->
                    val until = TimeUtils.clockText(base + minutes * 60_000L)
                    val cd = stringResource(R.string.extend_option_cd, minutes, until)
                    Surface(
                        onClick = { onPick(minutes) },
                        shape = MaterialTheme.shapes.large,
                        color = AppTheme.colors.group,
                        modifier = Modifier.weight(1f).height(88.dp).semantics { contentDescription = cd },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("+$minutes", style = MaterialTheme.typography.titleLarge)
                            Text(until, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.extend_unit_hint),
                style = MaterialTheme.typography.labelMedium,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 10.dp),
            )
            Surface(
                color = AppTheme.colors.group,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = null, tint = c.onSurfaceVariant)
                    Text(stringResource(R.string.extend_note), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}
