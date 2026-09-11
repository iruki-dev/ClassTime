package dev.iruki.classtime.util

import android.content.Context

/**
 * 작은 사용자 설정 몇 개. Room 에 넣을 만큼 구조적이지 않아 SharedPreferences 로 둔다.
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("classtime_settings", Context.MODE_PRIVATE)

    /**
     * 대기 모드. 켜져 있으면 앱을 열 때 녹음 서비스를 미리 띄워 두고, 수업 시간에는
     * 그 서비스가 그대로 녹음을 시작한다. 자동 녹음에 소리가 들어오게 하는 핵심 장치다.
     */
    var standbyEnabled: Boolean
        get() = prefs.getBoolean(KEY_STANDBY, true)
        set(value) = prefs.edit().putBoolean(KEY_STANDBY, value).apply()

    private companion object {
        const val KEY_STANDBY = "standby_enabled"
    }
}
