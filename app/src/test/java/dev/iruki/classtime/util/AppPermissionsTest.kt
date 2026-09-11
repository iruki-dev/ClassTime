package dev.iruki.classtime.util

import android.Manifest
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * 권한 점검 모델 테스트.
 *
 * 무음 녹음의 실제 해법은 '대기 모드'(앱이 열린 동안 서비스를 미리 띄워 마이크 권한을 잡아 두는 것)
 * 이고, '다른 앱 위에 표시'는 일부 기기에서만 통하는 **보조 수단**이다. 그래서 이 항목은
 * 안내는 하되 심각(critical) 으로 올려 사용자를 오도하면 안 된다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppPermissionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        grantMic()
    }

    private fun grantMic() {
        Shadows.shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun setOverlay(allowed: Boolean) = ShadowSettings.setCanDrawOverlays(allowed)

    private fun setBatteryExempt(exempt: Boolean) {
        val pm = Shadows.shadowOf(
            context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        )
        pm.setIgnoringBatteryOptimizations(context.packageName, exempt)
    }

    @Test
    fun withoutOverlayPermission_backgroundMicIsSuggestedButNotCritical() {
        setOverlay(false)
        val issues = AppPermissions.issues(context)
        val bg = issues.firstOrNull { it.id == SetupId.BACKGROUND_MIC }
        assertThat(bg).isNotNull()
        // 이 권한만으로는 무음 문제가 해결되지 않는 기기가 있다(실제 사용자 기기가 그랬다).
        // 해결책인 것처럼 심각 항목으로 띄우면 사용자가 잘못된 곳을 계속 붙잡게 된다.
        assertThat(bg!!.critical).isFalse()
        assertThat(AppPermissions.canRecordFromBackground(context)).isFalse()
    }

    @Test
    fun micPermission_isTheOnlyCriticalItem() {
        // 마이크 권한이 없으면 녹음 자체가 불가능하다. 그 외에는 전부 품질·보조 항목이다.
        setOverlay(false)
        assertThat(AppPermissions.issues(context).filter { it.critical }).isEmpty()
    }

    @Test
    fun withOverlayPermission_backgroundMicIsNotReported() {
        setOverlay(true)
        assertThat(AppPermissions.canRecordFromBackground(context)).isTrue()
        assertThat(AppPermissions.issues(context).map { it.id })
            .doesNotContain(SetupId.BACKGROUND_MIC)
    }

    @Test
    fun criticalIssuesComeFirst() {
        setOverlay(false)
        setBatteryExempt(false)
        val issues = AppPermissions.issues(context)
        assertThat(issues).isNotEmpty()
        // 심각 항목이 먼저 오고, 그 뒤로는 심각하지 않은 것만 남아야 한다.
        val firstNonCritical = issues.indexOfFirst { !it.critical }
        if (firstNonCritical >= 0) {
            assertThat(issues.drop(firstNonCritical).none { it.critical }).isTrue()
        }
    }

    @Test
    fun backgroundMicIssue_opensOverlaySettings() {
        val intent = AppPermissions.settingsIntent(context, SetupId.BACKGROUND_MIC)
        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        assertThat(intent.data.toString()).isEqualTo("package:${context.packageName}")
    }

    @Test
    fun runtimePermissionIssues_haveNoSettingsIntent() {
        // 마이크·알림은 설정 화면이 아니라 런타임 권한 요청으로 처리해야 한다.
        assertThat(AppPermissions.settingsIntent(context, SetupId.MICROPHONE)).isNull()
        assertThat(AppPermissions.settingsIntent(context, SetupId.NOTIFICATIONS)).isNull()
    }

    @Test
    fun runtimePermissions_onApi33_includeNotificationsButNotStorage() {
        val perms = AppPermissions.runtimePermissions().toList()
        assertThat(perms).contains(Manifest.permission.RECORD_AUDIO)
        assertThat(perms).contains(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(perms).doesNotContain(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Test
    @Config(sdk = [28])
    fun runtimePermissions_onApi28_includeStorageButNotNotifications() {
        val perms = AppPermissions.runtimePermissions().toList()
        assertThat(perms).contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertThat(perms).doesNotContain(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    @Config(sdk = [28])
    fun onApi28_backgroundMicIsUnrestricted() {
        // Android 10 이하에는 백그라운드 마이크 제약이 없다. 불필요한 경고를 띄우면 안 된다.
        setOverlay(false)
        assertThat(AppPermissions.canRecordFromBackground(context)).isTrue()
        assertThat(AppPermissions.issues(context).map { it.id })
            .doesNotContain(SetupId.BACKGROUND_MIC)
    }
}
