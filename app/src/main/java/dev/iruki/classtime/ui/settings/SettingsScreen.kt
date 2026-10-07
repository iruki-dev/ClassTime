package dev.iruki.classtime.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.iruki.classtime.BuildConfig
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.RescanResult
import dev.iruki.classtime.ui.common.AppSwitch
import dev.iruki.classtime.ui.common.DetailTopBar
import dev.iruki.classtime.ui.common.GroupGap
import dev.iruki.classtime.ui.common.GroupRow
import dev.iruki.classtime.ui.common.RowHeadline
import dev.iruki.classtime.ui.common.RowSupporting
import dev.iruki.classtime.ui.common.ScreenPadding
import dev.iruki.classtime.ui.theme.AppTheme
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.SetupId
import dev.iruki.classtime.util.SetupIssue
import dev.iruki.classtime.util.SystemScreens
import dev.iruki.classtime.util.ThemeMode

/** 줄 하나를 그리는 함수. (index, count) 를 받아 묶음 모양을 정한다. */
private typealias SettingRow = @Composable (Int, Int) -> Unit

/**
 * 설정. 값은 제목 아래 한 줄 요약으로 보여 주고, 줄을 누르면 실제로 그 일이 일어난다
 * (권한 → 그 시스템 화면, 저장 위치 → 파일 앱의 그 폴더).
 */
@Composable
fun SettingsScreen(
    setupIssues: List<SetupIssue>,
    onResolveIssue: (SetupIssue) -> Unit,
    onBack: () -> Unit,
    onOpenLabs: () -> Unit,
) {
    val vm: SettingsViewModel = hiltViewModel()
    val context = LocalContext.current
    val standbyEnabled by vm.standbyEnabled.collectAsStateWithLifecycle()
    val standby by vm.standby.collectAsStateWithLifecycle()
    val reminder by vm.reminderMinutes.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val rescanning by vm.rescanning.collectAsStateWithLifecycle()
    val rescanResult by vm.rescanResult.collectAsStateWithLifecycle()
    val rebuild by vm.rebuild.collectAsStateWithLifecycle()
    val aiSummary by vm.aiSummary.collectAsStateWithLifecycle()
    var pickReminder by remember { mutableStateOf(false) }

    // 재설치 전 녹음·PC 에서 넣은 파일은 오디오 읽기 권한이 있어야 보인다. 거절해도 이 앱이 만든
    // 파일은 보이므로 결과와 상관없이 검사한다.
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.rescan() }

    val recordRows = listOf<SettingRow>(
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { vm.setStandbyEnabled(!standbyEnabled) },
                leading = { RowIcon(Icons.Rounded.VerifiedUser) },
                supporting = {
                    RowSupporting(
                        stringResource(
                            if (standbyEnabled && !standby.readyForSilentFreeRecording) R.string.settings_standby_preparing
                            else R.string.settings_standby_summary
                        )
                    )
                },
                trailing = { AppSwitch(checked = standbyEnabled, onCheckedChange = vm::setStandbyEnabled) },
            ) { RowHeadline(stringResource(R.string.home_standby_title)) }
        },
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { pickReminder = true },
                leading = { RowIcon(Icons.Rounded.NotificationsActive) },
                supporting = { RowSupporting(reminderLabel(reminder)) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(R.string.settings_reminder)) }
        },
    )

    val permissionRows = permissionChecks(setupIssues).map<PermissionCheck, SettingRow> { check ->
        { i, n ->
            val issue = setupIssues.firstOrNull { it.id == check.id }
            GroupRow(
                index = i, count = n,
                onClick = { if (issue != null) onResolveIssue(issue) else SystemScreens.openFor(context, check.id) },
                leading = { RowIcon(check.icon) },
                supporting = {
                    RowSupporting(
                        stringResource(if (issue == null) R.string.settings_ok else R.string.settings_needed),
                        color = if (issue == null) MaterialTheme.colorScheme.primary else AppTheme.colors.caution,
                    )
                },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(check.title)) }
        }
    }

    val storageRows = listOf<SettingRow>(
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { SystemScreens.openRecordingsFolder(context) },
                leading = { RowIcon(Icons.Rounded.FolderOpen) },
                supporting = { RowSupporting(stringResource(R.string.settings_storage_path, RecordingStorage.ROOT)) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.OpenInNew) },
            ) { RowHeadline(stringResource(R.string.settings_storage_location)) }
        },
        { i, n ->
            GroupRow(
                index = i, count = n,
                leading = { RowIcon(Icons.Rounded.ManageSearch) },
                supporting = when {
                    rescanning -> { { RowSupporting(stringResource(R.string.settings_rescan_running)) } }
                    rescanResult != null -> { { RowSupporting(rescanText(rescanResult!!)) } }
                    else -> null
                },
                trailing = {
                    if (rescanning) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp), strokeWidth = 3.dp)
                    } else {
                        FilledTonalButton(onClick = { audioPermission.launch(AppPermissions.audioReadPermission()) }) {
                            Text(stringResource(R.string.settings_rescan_action))
                        }
                    }
                },
            ) { RowHeadline(stringResource(R.string.settings_rescan)) }
        },
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = vm::startRebuild,
                leading = { RowIcon(Icons.Rounded.AutoAwesome) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(R.string.settings_rebuild)) }
        },
    )

    val aboutRows = listOf<SettingRow>(
        { i, n ->
            GroupRow(
                index = i, count = n,
                onClick = { SystemScreens.openAppDetails(context) },
                leading = { RowIcon(Icons.Rounded.Info) },
                supporting = { RowSupporting(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.OpenInNew) },
            ) { RowHeadline(stringResource(R.string.settings_app_info)) }
        },
    )

    val labsRows = listOf<SettingRow>(
        { i, n ->
            val (on, pending) = aiSummary
            GroupRow(
                index = i, count = n,
                onClick = onOpenLabs,
                leading = { RowIcon(Icons.Rounded.Science) },
                supporting = {
                    RowSupporting(
                        when {
                            !on -> stringResource(R.string.ai_summary_off)
                            pending > 0 -> stringResource(R.string.ai_summary_on_queue, pending)
                            else -> stringResource(R.string.ai_summary_on)
                        }
                    )
                },
                trailing = { RowIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight) },
            ) { RowHeadline(stringResource(R.string.ai_title)) }
        },
    )

    Scaffold(
        containerColor = AppTheme.colors.page,
        topBar = { DetailTopBar(stringResource(R.string.settings_title), onNavigate = onBack) },
    ) { inner ->
        LazyColumn(Modifier.padding(inner), contentPadding = PaddingValues(bottom = 24.dp)) {
            section(R.string.settings_section_record, recordRows)
            section(R.string.settings_section_permissions, permissionRows)
            item(key = "h_display") { SectionTitle(R.string.settings_section_display) }
            item(key = "theme") {
                ThemeGroup(theme, vm::setThemeMode, Modifier.padding(horizontal = ScreenPadding))
            }
            section(R.string.settings_section_storage, storageRows)
            section(R.string.settings_section_about, aboutRows)
            section(R.string.settings_section_labs, labsRows)
        }
    }

    if (pickReminder) {
        ReminderDialog(
            current = reminder,
            onDismiss = { pickReminder = false },
            onPick = { vm.setReminderMinutes(it); pickReminder = false },
        )
    }

    when (val r = rebuild) {
        is RebuildState.Preview -> RebuildSheet(r, onApply = vm::applyRebuild, onDismiss = vm::dismissRebuild)
        is RebuildState.Done -> AlertDialog(
            onDismissRequest = vm::dismissRebuild,
            text = { Text(stringResource(R.string.rebuild_done, r.added)) },
            confirmButton = { TextButton(onClick = vm::dismissRebuild) { Text(stringResource(R.string.action_confirm)) } },
        )
        else -> Unit
    }
}

private data class PermissionCheck(val id: SetupId, val icon: ImageVector, @StringRes val title: Int)

/** 이 기기에서 의미가 있는 권한만. ‘다른 앱 위에 표시’는 아직 허용하지 않았을 때만 보인다. */
private fun permissionChecks(issues: List<SetupIssue>): List<PermissionCheck> = buildList {
    add(PermissionCheck(SetupId.MICROPHONE, Icons.Rounded.Mic, R.string.settings_mic))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(PermissionCheck(SetupId.EXACT_ALARM, Icons.Rounded.Alarm, R.string.setup_exact_alarm_title))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionCheck(SetupId.NOTIFICATIONS, Icons.Rounded.Notifications, R.string.setup_notifications_title))
    }
    add(PermissionCheck(SetupId.BATTERY, Icons.Rounded.BatterySaver, R.string.setup_battery_title))
    if (issues.any { it.id == SetupId.BACKGROUND_MIC }) {
        add(PermissionCheck(SetupId.BACKGROUND_MIC, Icons.Rounded.Layers, R.string.settings_overlay))
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 16.dp)
            .heightIn(min = 40.dp)
            .padding(top = 12.dp),
    )
}

private fun LazyListScope.section(@StringRes title: Int, rows: List<SettingRow>) {
    item(key = "h_$title") { SectionTitle(title) }
    itemsIndexed(rows, key = { i, _ -> "r_${title}_$i" }) { i, row ->
        Box(Modifier.padding(horizontal = ScreenPadding, vertical = GroupGap / 2)) { row(i, rows.size) }
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun reminderLabel(minutes: Int): String =
    if (minutes <= 0) stringResource(R.string.settings_reminder_off)
    else stringResource(R.string.settings_reminder_minutes, minutes)

@Composable
private fun rescanText(r: RescanResult): String = when {
    r.added > 0 && r.removed > 0 -> stringResource(R.string.settings_rescan_both, r.added, r.removed)
    r.added > 0 -> stringResource(R.string.settings_rescan_added, r.added)
    r.removed > 0 -> stringResource(R.string.settings_rescan_removed, r.removed)
    else -> stringResource(R.string.settings_rescan_same)
}

/** 테마: 제목 줄 + 그 아래 시스템/라이트/다크 연결 버튼 그룹(간격 2, 바깥 끝 원, 안쪽 8). */
@Composable
private fun ThemeGroup(current: ThemeMode, onPick: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(color = AppTheme.colors.group, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Rounded.Contrast)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            }
            val options = listOf(
                Triple(ThemeMode.SYSTEM, Icons.Rounded.BrightnessAuto, R.string.settings_theme_system),
                Triple(ThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.settings_theme_light),
                Triple(ThemeMode.DARK, Icons.Rounded.DarkMode, R.string.settings_theme_dark),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEachIndexed { i, (mode, icon, label) ->
                    val selected = mode == current
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 8.dp, bottomEnd = 8.dp)
                        options.lastIndex -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 24.dp, bottomEnd = 24.dp)
                        else -> RoundedCornerShape(8.dp)
                    }
                    Surface(
                        shape = shape,
                        color = if (selected) c.secondaryContainer else c.surfaceContainer,
                        contentColor = if (selected) c.onSecondaryContainer else c.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onPick(mode) },
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** 수업 전 알림 시점. 선택지가 다섯이라 줄 안 칩 대신 라디오 대화상자(구글 앱 설정과 같은 방식). */
@Composable
private fun ReminderDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppSettings.REMINDER_CHOICES.forEach { minutes ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(selected = minutes == current, role = Role.RadioButton) { onPick(minutes) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = minutes == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(reminderLabel(minutes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
