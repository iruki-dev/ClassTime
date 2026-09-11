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
import androidx.core.content.ContextCompat

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

data class SetupIssue(
    val id: SetupId,
    val title: String,
    val detail: String,
    val actionLabel: String,
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
                    title = "마이크 권한이 필요합니다",
                    detail = "권한이 없으면 녹음을 시작할 수 없습니다.",
                    actionLabel = "권한 허용",
                    critical = true,
                )
            )
        }
        if (!canRecordFromBackground(context)) {
            add(
                SetupIssue(
                    id = SetupId.BACKGROUND_MIC,
                    title = "‘다른 앱 위에 표시’ (선택)",
                    detail = "일부 기기에서는 이 권한이 백그라운드 마이크 제한의 예외로 인정됩니다. " +
                        "자동 녹음 소리 문제는 주로 아래 ‘대기 모드’로 해결되지만, 켜 두면 " +
                        "안전장치가 하나 더 생깁니다.",
                    actionLabel = "설정 열기",
                    critical = false,
                )
            )
        }
        if (!notificationsGranted(context)) {
            add(
                SetupIssue(
                    id = SetupId.NOTIFICATIONS,
                    title = "알림 권한이 꺼져 있습니다",
                    detail = "녹음 중 알림은 포그라운드 서비스 유지에 필요합니다. " +
                        "무음 녹음 경고도 이 알림으로 전달됩니다.",
                    actionLabel = "권한 허용",
                    critical = false,
                )
            )
        }
        if (!canScheduleExactAlarms(context)) {
            add(
                SetupIssue(
                    id = SetupId.EXACT_ALARM,
                    title = "‘알람 및 리마인더’가 꺼져 있습니다",
                    detail = "꺼져 있으면 시스템이 알람을 몇 분씩 미루기 때문에 수업 앞부분이 잘립니다.",
                    actionLabel = "설정 열기",
                    critical = false,
                )
            )
        }
        if (!isIgnoringBatteryOptimizations(context)) {
            add(
                SetupIssue(
                    id = SetupId.BATTERY,
                    title = "배터리 최적화 대상입니다",
                    detail = "제조사 절전 기능이 앱을 종료하면 자동 녹음이 통째로 실패할 수 있습니다.",
                    actionLabel = "제한 없음으로 변경",
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
