package dev.iruki.classtime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.schedule.ScheduleManager
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.ui.ClassTimeNavHost
import dev.iruki.classtime.ui.theme.ClassTimeTheme
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.AppPermissions
import dev.iruki.classtime.util.AppSettings
import dev.iruki.classtime.util.SetupId
import dev.iruki.classtime.util.SetupIssue
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: ClassTimeRepository
    @Inject lateinit var scheduleManager: ScheduleManager
    @Inject lateinit var settings: AppSettings

    private var setupIssues by mutableStateOf<List<SetupIssue>>(emptyList())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetupIssues()
            reschedule()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!AppPermissions.allRuntimeGranted(this)) {
            permissionLauncher.launch(AppPermissions.runtimePermissions())
        }
        refreshSetupIssues()

        setContent {
            ClassTimeTheme {
                ClassTimeNavHost(
                    setupIssues = setupIssues,
                    onResolveIssue = ::resolve,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupIssues()
        reschedule()
        ensureStandby()
    }

    /**
     * 대기 모드를 켠다. **여기서 켜는 것이 중요하다** — 안드로이드는 서비스가 포그라운드로
     * 전환되는 순간의 앱 상태로 마이크 접근 권한을 고정한다. 액티비티가 화면에 있는 지금
     * 올려 두어야, 나중에 수업 시간 알람이 백그라운드에서 울려도 소리가 녹음된다.
     */
    private fun ensureStandby() {
        lifecycleScope.launch {
            if (!settings.standbyEnabled) return@launch
            if (!AppPermissions.micGranted(this@MainActivity)) return@launch
            val hasAutoCourse = withContext(Dispatchers.IO) {
                runCatching { repository.allCourses().any { it.autoRecord } }
                    .onFailure { AppLog.e(TAG, "과목 조회 실패 - 대기 모드를 켜지 못했습니다", it) }
                    .getOrDefault(false)
            }
            if (hasAutoCourse) RecordingService.startStandby(this@MainActivity)
        }
    }

    private fun refreshSetupIssues() {
        setupIssues = AppPermissions.issues(this)
    }

    /** 설정 항목 하나를 해결한다. 런타임 권한이면 요청하고, 특별 권한이면 설정 화면을 연다. */
    private fun resolve(issue: SetupIssue) {
        when (issue.id) {
            SetupId.MICROPHONE, SetupId.NOTIFICATIONS ->
                permissionLauncher.launch(AppPermissions.runtimePermissions())

            else -> {
                val intent = AppPermissions.settingsIntent(this, issue.id)
                if (intent == null || !launchSafely(intent)) {
                    launchSafely(AppPermissions.fallbackIntent(this, issue.id))
                }
            }
        }
    }

    private fun launchSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun reschedule() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 여기서 조용히 실패하면 알람이 하나도 걸리지 않아 자동 녹음이 통째로 멈춘다.
            runCatching {
                scheduleManager.rescheduleAll()
                scheduleManager.catchUpIfMidClass()
            }.onFailure { AppLog.e(TAG, "알람 재설정 실패 - 자동 녹음이 동작하지 않습니다", it) }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
