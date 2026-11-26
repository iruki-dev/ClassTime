package dev.iruki.classtime.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.iruki.classtime.R

/** 텍스트·요약이 있는 녹음을 보낼 때: 녹음 파일·텍스트 파일·요약 파일 중 무엇을 보낼지. */
@Composable
fun ExportDialog(
    available: RecordingExport.Available,
    onDismiss: () -> Unit,
    onSend: (audio: Boolean, text: Boolean, summary: Boolean) -> Unit,
) {
    var audio by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf(available.text) }
    var summary by rememberSaveable { mutableStateOf(available.summary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Choice(stringResource(R.string.export_audio), audio) { audio = it }
                if (available.text) Choice(stringResource(R.string.export_text), text) { text = it }
                if (available.summary) Choice(stringResource(R.string.export_summary), summary) { summary = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(audio, text && available.text, summary && available.summary) }, enabled = audio || text || summary) {
                Text(stringResource(R.string.export_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
