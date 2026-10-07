package dev.iruki.classtime.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.BuildConfig
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.DetailTopBar
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.common.SectionHeader
import dev.iruki.classtime.ui.common.StatusLabel
import dev.iruki.classtime.ui.home.HomeViewModel
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.util.SetupId
import dev.iruki.classtime.util.SetupIssue

private data class Check(
    val id: SetupId,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val detail: Int,
)

/** 이 기기에서 의미가 있는 점검 항목. 없는 권한(구 버전 안드로이드)은 보여 주지 않는다. */
private fun checks(): List<Check> = buildList {
    add(Check(SetupId.MICROPHONE, Icons.Rounded.Mic, R.string.setup_mic_title, R.string.setup_mic_detail))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Check(SetupId.EXACT_ALARM, Icons.Rounded.Alarm, R.string.setup_exact_alarm_title, R.string.setup_exact_alarm_detail))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Check(SetupId.NOTIFICATIONS, Icons.Rounded.Notifications, R.string.setup_notifications_title, R.string.setup_notifications_detail))
    }
    add(Check(SetupId.BATTERY, Icons.Rounded.BatterySaver, R.string.setup_battery_title, R.string.setup_battery_detail))
}

@Composable
fun SettingsScreen(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    onBack: () -> Unit,
) {
    val vm: HomeViewModel = hiltViewModel()
    val standbyEnabled by vm.standbyEnabled.collectAsStateWithLifecycle()
    val standby by vm.standby.collectAsStateWithLifecycle()

    val overlay = setupIssues.firstOrNull { it.id == SetupId.BACKGROUND_MIC }
    val autoRows = buildList<@Composable (Int, Int) -> Unit> {
        add { i, n ->
            GroupRow(
                index = i, count = n, minHeight = 88.dp,
                onClick = { vm.setStandbyEnabled(!standbyEnabled) },
                leading = { Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                supporting = {
                    RowSupporting(
                        stringResource(
                            if (standbyEnabled && !standby.readyForSilentFreeRecording) R.string.home_standby_line_preparing
                            else R.string.home_standby_detail
                        )
                    )
                },
                trailing = { AppSwitch(checked = standbyEnabled, onCheckedChange = vm::setStandbyEnabled) },
            ) { RowHeadline(stringResource(R.string.home_standby_title)) }
        }
        checks().forEach { check ->
            val issue = setupIssues.firstOrNull { it.id == check.id }
            add { i, n -> CheckRow(i, n, check.icon, check.title, check.detail, issue, onResolveIssue) }
        }
        if (overlay != null) add { i, n ->
            CheckRow(i, n, Icons.Rounded.Layers, overlay.title, overlay.detail, overlay, onResolveIssue)
        }
    }

    Scaffold(
        containerColor = AppTheme.colors.page,
        topBar = { DetailTopBar(stringResource(R.string.settings_title), onNavigate = onBack) },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(bottom = 24.dp)) {
            section(R.string.settings_section_auto, autoRows)
            section(
                R.string.settings_section_storage,
                listOf(
                    { i, n -> InfoRow(i, n, Icons.Rounded.FolderOpen, stringResource(R.string.settings_storage_location), stringResource(R.string.settings_storage_path, RecordingStorage.ROOT)) },
                    { i, n -> InfoRow(i, n, Icons.Rounded.Usb, stringResource(R.string.settings_pc_title), stringResource(R.string.settings_pc_detail)) },
                ),
            )
            section(
                R.string.settings_section_about,
                listOf({ i, n -> InfoRow(i, n, Icons.Rounded.Info, stringResource(R.string.settings_version), "ClassTime ${BuildConfig.VERSION_NAME}") }),
            )
        }
    }
}

private fun LazyListScope.section(@StringRes title: Int, rows: List<@Composable (Int, Int) -> Unit>) {
    item(key = "h_$title") {
        SectionHeader(stringResource(title), Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp))
    }
    itemsIndexed(rows, key = { i, _ -> "r_${title}_$i" }) { i, row ->
        Box(Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2)) { row(i, rows.size) }
    }
}

@Composable
private fun CheckRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes detail: Int,
    issue: SetupIssue?,
    onResolve: (SetupIssue) -> Unit,
) {
    val colors = AppTheme.colors
    GroupRow(
        index = index, count = count,
        leading = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supporting = { RowSupporting(stringResource(detail)) },
        trailing = {
            if (issue != null) {
                FilledTonalButton(
                    onClick = { onResolve(issue) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.cautionContainer,
                        contentColor = colors.onCautionContainer,
                    ),
                ) { Text(stringResource(issue.actionLabel)) }
            } else {
                StatusLabel(stringResource(R.string.settings_ok), Icons.Rounded.CheckCircle, MaterialTheme.colorScheme.primary)
            }
        },
    ) { RowHeadline(stringResource(title)) }
}

@Composable
private fun InfoRow(index: Int, count: Int, icon: ImageVector, title: String, detail: String) {
    GroupRow(
        index = index, count = count,
        leading = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        supporting = { RowSupporting(detail) },
    ) { RowHeadline(title) }
}
