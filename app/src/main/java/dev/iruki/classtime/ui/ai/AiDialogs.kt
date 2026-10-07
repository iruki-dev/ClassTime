package dev.iruki.classtime.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.R

/**
 * 키 넣기 시트. ‘확인하고 저장’을 누르면 키를 실제로 한 번 써 보고, 되는 키만 저장한다.
 * Groq 키를 처음 넣으면 저장 전에 외부 전송 안내를 띄운다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeySheet(service: KeyService, savedHint: String?, vm: AiLabsViewModel, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val check by vm.keyCheck.collectAsStateWithLifecycle()
    var key by rememberSaveable(service) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val groq = service == KeyService.GROQ
    val name = if (groq) "Groq" else "NVIDIA"
    val host = if (groq) "console.groq.com" else "build.nvidia.com"
    val url = if (groq) "https://console.groq.com/keys" else "https://build.nvidia.com/settings/api-keys"

    LaunchedEffect(check) { if (check == KeyCheck.Saved) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = c.secondaryContainer, contentColor = c.onSecondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (groq) Icons.Rounded.GraphicEq else Icons.Rounded.AutoFixHigh, contentDescription = null)
                    }
                }
                Column {
                    Text(stringResource(R.string.ai_key_title, name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(if (groq) R.string.ai_key_groq_detail else R.string.ai_key_nvidia_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                    )
                }
            }

            val error = check == KeyCheck.Invalid || check == KeyCheck.Offline
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); if (error) vm.resetKeyCheck() },
                label = { Text(stringResource(R.string.ai_key_field)) },
                placeholder = savedHint?.let { { Text(it, fontFamily = FontFamily.Monospace) } },
                singleLine = true,
                isError = error,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = {
                    Text(
                        stringResource(
                            when (check) {
                                KeyCheck.Invalid -> R.string.ai_key_invalid
                                KeyCheck.Offline -> R.string.ai_key_offline
                                else -> R.string.ai_key_help
                            }
                        )
                    )
                },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(if (visible) R.string.ai_key_hide else R.string.ai_key_show),
                            )
                        }
                        if (error) {
                            Icon(Icons.Rounded.Error, contentDescription = null, tint = c.error, modifier = Modifier.padding(12.dp))
                        } else {
                            IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.ai_key_paste))
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )

            TextButton(onClick = { uri.openUri(url) }, modifier = Modifier.offset(x = (-12).dp).padding(top = 4.dp)) {
                Text(stringResource(R.string.ai_key_get, host))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }

            val checking = check == KeyCheck.Checking
            Button(
                onClick = { vm.checkKey(service, key) },
                enabled = key.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 0.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = c.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ai_key_checking), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(stringResource(R.string.ai_key_save), style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                if (savedHint != null) {
                    TextButton(onClick = { vm.deleteKey(service) }) {
                        Text(stringResource(R.string.ai_key_delete), color = c.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }

    (check as? KeyCheck.NeedsConsent)?.let {
        ConsentDialog(onAccept = { vm.acceptConsent(service) }, onDismiss = vm::resetKeyCheck)
    }
}

/** 처음 켤 때 한 번: 녹음과 글이 어디로 가는지. */
@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
        title = { Text(stringResource(R.string.ai_consent_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConsentLine(Icons.Rounded.GraphicEq, "Groq", stringResource(R.string.ai_consent_groq))
                ConsentLine(Icons.Rounded.AutoFixHigh, "NVIDIA", stringResource(R.string.ai_consent_nvidia))
                ConsentLine(Icons.Rounded.Policy, stringResource(R.string.ai_consent_terms_k), stringResource(R.string.ai_consent_terms), joined = true)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.ai_consent_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = c.surfaceContainerHigh,
    )
}

@Composable
private fun ConsentLine(icon: androidx.compose.ui.graphics.vector.ImageVector, key: String, value: String, joined: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.onSurface)) { append(key) }
                if (!joined) append(' ')
                append(value)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
        )
    }
}

/** 교정 모델 고르기. 지금 제공되지 않는 모델은 흐리게, 고를 수 없게. */
@Composable
internal fun ModelDialog(models: List<ModelChoice>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var choice by remember(selected) { mutableStateOf(selected) }
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_model)) },
        text = {
            Column(Modifier.selectableGroup()) {
                models.forEachIndexed { i, m ->
                    val on = m.id == choice
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(selected = on, enabled = m.available, role = Role.RadioButton) { choice = m.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = on, onClick = null, enabled = m.available)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, style = MaterialTheme.typography.bodyLarge, color = if (m.available) c.onSurface else c.onSurface.copy(alpha = 0.38f))
                                if (i == 0) {
                                    Text(
                                        stringResource(R.string.ai_model_default),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = c.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            Text(
                                if (m.available) m.id else stringResource(R.string.ai_model_gone),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = c.onSurfaceVariant.copy(alpha = if (m.available) 1f else 0.38f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
