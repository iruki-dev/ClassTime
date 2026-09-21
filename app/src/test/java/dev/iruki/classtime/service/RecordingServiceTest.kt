package dev.iruki.classtime.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.data.ClassTimeRepository
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 서비스의 **대기 모드 상태 기계**를 검증한다. 실제 녹음(MediaRecorder)은 건드리지 않는다.
 *
 * 여기서 지키려는 불변식은 코드 주석에 크게 적혀 있는 바로 그것이다:
 * `startForeground()` 는 인스턴스당 한 번만 불러야 하고, 그 순간 앱이 화면에 있었는지가
 * [dev.iruki.classtime.data.StandbyState.micReady] 로 남아야 한다. 이 값이 틀리면
 * 서비스는 멀쩡히 떠 있는데 녹음만 무음으로 나온다 — 사용자가 가장 알아채기 어려운 실패다.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class RecordingServiceTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repo: ClassTimeRepository

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        // HiltTestApplication 이 ClassTimeApp 을 대체하므로 채널이 만들어지지 않는다.
        // 알림을 올리려면 채널이 있어야 해서 여기서 직접 만든다.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        listOf(ClassTimeApp.CHANNEL_RECORDING, ClassTimeApp.CHANNEL_WARNING).forEach {
            nm.createNotificationChannel(
                NotificationChannel(it, it, NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun standbyIntent(fromForeground: Boolean) =
        Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_STANDBY
            putExtra(RecordingService.EXTRA_FROM_FOREGROUND, fromForeground)
        }

    private fun stopStandbyIntent() =
        Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_STANDBY
        }

    @Test
    fun standbyFromForeground_reportsMicReady() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(true))
            .create().startCommand(0, 0)

        val state = repo.standby.value
        assertThat(state.active).isTrue()
        assertThat(state.micReady).isTrue()
        // 이 조합이어야 수업 시간에 소리가 담긴 녹음이 된다.
        assertThat(state.readyForSilentFreeRecording).isTrue()

        controller.destroy()
    }

    /**
     * 백그라운드(부팅 직후 등)에서 올라온 대기 모드는 마이크를 못 쥔 상태다.
     * 서비스가 살아 있다는 이유로 "준비 완료"라고 보고하면 안 된다.
     */
    @Test
    fun standbyFromBackground_isActiveButNotMicReady() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(false))
            .create().startCommand(0, 0)

        val state = repo.standby.value
        assertThat(state.active).isTrue()
        assertThat(state.micReady).isFalse()
        assertThat(state.readyForSilentFreeRecording).isFalse()

        controller.destroy()
    }

    /**
     * 마이크 없이 떠 있던 서비스에 **앱이 화면에 올라온 상태로** 대기 요청이 다시 오면
     * 일부러 포그라운드를 내렸다 올려 권한을 새로 잡는다.
     * 이것이 "앱을 한 번 열면 다음 수업부터 정상"을 성립시키는 장치다.
     */
    @Test
    fun reenteringFromForeground_recoversMicAccess() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(false))
            .create().startCommand(0, 0)
        assertThat(repo.standby.value.micReady).isFalse()

        controller.withIntent(standbyIntent(true)).startCommand(0, 0)

        assertThat(repo.standby.value.micReady).isTrue()
        assertThat(repo.standby.value.readyForSilentFreeRecording).isTrue()

        controller.destroy()
    }

    /** 이미 마이크를 쥐고 있으면 반복 요청에도 그 상태를 잃지 않아야 한다. */
    @Test
    fun repeatedStandbyRequests_keepMicReady() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(true))
            .create().startCommand(0, 0)

        repeat(3) { controller.withIntent(standbyIntent(true)).startCommand(0, 0) }

        assertThat(repo.standby.value.micReady).isTrue()
        controller.destroy()
    }

    @Test
    fun stopStandby_clearsTheStandbyState() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(true))
            .create().startCommand(0, 0)
        assertThat(repo.standby.value.active).isTrue()

        controller.withIntent(stopStandbyIntent()).startCommand(0, 0)

        assertThat(repo.standby.value.active).isFalse()
        controller.destroy()
    }

    @Test
    fun destroyingTheService_clearsTheStandbyState() {
        val controller = Robolectric.buildService(RecordingService::class.java, standbyIntent(true))
            .create().startCommand(0, 0)

        controller.destroy()

        assertThat(repo.standby.value.active).isFalse()
        assertThat(repo.standby.value.micReady).isFalse()
    }
}
