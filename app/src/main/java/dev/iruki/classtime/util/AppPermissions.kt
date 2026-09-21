package dev.iruki.classtime.util

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import dev.iruki.classtime.R

/** 자동 녹음이 제대로 동작하려면 필요한 설정 항목. */
enum class SetupId {
    /** 마이크 런타임 권한. 없으면 녹음 자체가 불가능. */
    MICROPHONE,

    /** 알림 권한(Android 13+). 포그라운드 서비스 알림에 필요. */
    NOTIFICATIONS,

    /** 정확 알람(Android 12+). 없으면 수업 시작보다 늦게 켜진다. */
    EXACT_ALARM,

    /**
     * **백그라운드 마이크 접근 허용.**
     *
     * Android 11(API 30)부터, 앱이 백그라운드일 때 시작된 포그라운드 서비스는
     * 마이크에 접근할 수 없다. 예외를 던지지 않고 **무음이 그대로 녹음된다.**
     * 수업 시간 알람은 당연히 백그라운드에서 울리므로 자동 녹음이 전부 무음이 된다.
     *
     * AOSP 가 인정하는 예외 중 일반 앱이 얻을 수 있는 것은 `SYSTEM_ALERT_WINDOW`
     * ('다른 앱 위에 표시') 뿐이다. 이 권한이 있으면 백그라운드에서 시작된
     * 포그라운드 서비스도 마이크를 정상적으로 쓸 수 있다.
     */
    BACKGROUND_MIC,

    /** 배터리 최적화 제외. 제조사 절전 기능이 알람·서비스를 죽이는 것을 막는다. */
    BATTERY,
}

/**
 * 문구를 String 이 아니라 리소스 id 로 들고 다닌다. 그래야 이 목록을 만드는 시점이 아니라
 * **화면에 그리는 시점의 로캘**로 번역되고, 기기 언어가 바뀌어도 그대로 따라간다.
 */
data class SetupIssue(
    val id: SetupId,
    @StringRes val title: Int,
    @StringRes val detail: Int,
    @StringRes val actionLabel: Int,
    /** true 면 자동 녹음이 실패하거나 무음이 된다. false 면 품질 저하 수준. */
    val critical: Boolean,
)

object AppPermissions {

    /** 런타임에 요청할 수 있는 일반 권한 목록. */
    fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    fun allRuntimeGranted(context: Context): Boolean =
        runtimePermissions().all { granted(context, it) }

    fun micGranted(context: Context): Boolean =
        granted(context, Manifest.permission.RECORD_AUDIO)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Android 12+ 에서 정확한 알람을 쓸 수 있는지. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * 백그라운드에서 시작된 포그라운드 서비스가 마이크를 쓸 수 있는지.
     * Android 10 이하는 제약이 없고, 11+ 는 '다른 앱 위에 표시' 권한이 예외가 된다.
     */
    fun canRecordFromBackground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /** 아직 해결되지 않은 설정 항목만. 심각한 것이 앞에 온다. */
    fun issues(context: Context): List<SetupIssue> = buildList {
        if (!micGranted(context)) {
            add(
                SetupIssue(
                    id = SetupId.MICROPHONE,
                    title = R.string.setup_mic_title,
                    detail = R.string.setup_mic_detail,
                    actionLabel = R.string.setup_action_grant,
                    critical = true,
                )
            )
        }
        if (!canRecordFromBackground(context)) {
            add(
                SetupIssue(
                    id = SetupId.BACKGROUND_MIC,
                    title = R.string.setup_overlay_title,
                    detail = R.string.setup_overlay_detail,
                    actionLabel = R.string.setup_action_open_settings,
                    critical = false,
                )
            )
        }
        if (!notificationsGranted(context)) {
            add(
                SetupIssue(
                    id = SetupId.NOTIFICATIONS,
                    title = R.string.setup_notifications_title,
                    detail = R.string.setup_notifications_detail,
                    actionLabel = R.string.setup_action_grant,
                    critical = false,
                )
            )
        }
        if (!canScheduleExactAlarms(context)) {
            add(
                SetupIssue(
                    id = SetupId.EXACT_ALARM,
                    title = R.string.setup_exact_alarm_title,
                    detail = R.string.setup_exact_alarm_detail,
                    actionLabel = R.string.setup_action_open_settings,
                    critical = false,
                )
            )
        }
        if (!isIgnoringBatteryOptimizations(context)) {
            add(
                SetupIssue(
                    id = SetupId.BATTERY,
                    title = R.string.setup_battery_title,
                    detail = R.string.setup_battery_detail,
                    actionLabel = R.string.setup_battery_action,
                    critical = false,
                )
            )
        }
    }.sortedByDescending { it.critical }

    /**
     * 해당 항목을 해결하는 설정 화면 Intent. null 이면 런타임 권한 요청으로 처리해야 한다.
     */
    fun settingsIntent(context: Context, id: SetupId): Intent? {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (id) {
            SetupId.MICROPHONE, SetupId.NOTIFICATIONS -> null
            SetupId.BACKGROUND_MIC ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            SetupId.EXACT_ALARM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                } else null
            SetupId.BATTERY ->
                @Suppress("BatteryLife")
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        }
    }

    /** [settingsIntent] 가 기기에서 열리지 않을 때 쓸 대체 화면. */
    fun fallbackIntent(context: Context, id: SetupId): Intent = when (id) {
        SetupId.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
