package dev.iruki.classtime.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 화면 테마. 기본은 기기 설정을 따른다. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 작은 사용자 설정 몇 개. Room 에 넣을 만큼 구조적이지 않아 SharedPreferences 로 둔다.
 * 화면이 바로 따라 바뀌어야 하는 값은 StateFlow 로도 내보낸다.
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

    private val _themeMode = MutableStateFlow(readTheme())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    private fun readTheme(): ThemeMode =
        prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM

    /** 수업 몇 분 전에 알려 줄지. 0 이면 끔. */
    private val _reminderMinutes = MutableStateFlow(prefs.getInt(KEY_REMINDER, DEFAULT_REMINDER))
    val reminderMinutes: StateFlow<Int> = _reminderMinutes.asStateFlow()

    fun setReminderMinutes(minutes: Int) {
        val value = minutes.coerceIn(0, 60)
        prefs.edit().putInt(KEY_REMINDER, value).apply()
        _reminderMinutes.value = value
    }

    companion object {
        private const val KEY_STANDBY = "standby_enabled"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_REMINDER = "reminder_minutes"

        const val DEFAULT_REMINDER = 10

        /** 설정 화면에서 고를 수 있는 알림 시점. 0 = 끔. */
        val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30)
    }
}
