package dev.iruki.classtime.schedule

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.iruki.classtime.service.RecordingService
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 알람이 울렸을 때 **무엇을 하고 무엇을 하지 않는지**를 고정한다.
 *
 * 특히 마지막 테스트가 중요하다. 예전에는 종료 알람이 "놓친 녹음 따라잡기"까지 실행해서
 * 방금 끝난 수업이 곧바로 다시 녹음되는 버그가 있었다. 그 분리를 회귀 테스트로 못박는다.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class AlarmReceiverTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
    }

    private fun deliver(action: String, extraKey: String? = null, id: Long = 0L) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            if (extraKey != null) putExtra(extraKey, id)
        }
        // 매니페스트에 등록된 리시버로 실제 브로드캐스트를 흘려보낸다.
        // 직접 onReceive() 를 부르면 goAsync() 가 PendingResult 없이 터진다.
        context.sendBroadcast(intent)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun startedService(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedService

    @Test
    fun startAlarmForACourse_startsAutoRecording() {
        deliver(ScheduleManager.ACTION_START, ScheduleManager.EXTRA_COURSE_ID, 7L)

        val started = startedService()
        assertThat(started).isNotNull()
        assertThat(started!!.action).isEqualTo(RecordingService.ACTION_START_AUTO)
        assertThat(started.getLongExtra(RecordingService.EXTRA_COURSE_ID, -1L)).isEqualTo(7L)
    }

    @Test
    fun startAlarmForAMakeup_startsMakeupRecording() {
        deliver(ScheduleManager.ACTION_START, ScheduleManager.EXTRA_EXCEPTION_ID, 3L)

        val started = startedService()
        assertThat(started).isNotNull()
        assertThat(started!!.action).isEqualTo(RecordingService.ACTION_START_MAKEUP)
        assertThat(started.getLongExtra(RecordingService.EXTRA_EXCEPTION_ID, -1L)).isEqualTo(3L)
    }

    @Test
    fun stopAlarm_sendsScheduledStop_soAnExtendedRecordingCanIgnoreIt() {
        deliver(ScheduleManager.ACTION_STOP)

        val started = startedService()
        assertThat(started).isNotNull()
        // 그냥 STOP 이면 사용자가 연장한 수업도 시간표 끝에서 잘린다.
        assertThat(started!!.action).isEqualTo(RecordingService.ACTION_STOP_SCHEDULED)
    }

    @Test
    fun skippedClass_isNotRecorded_butOtherDaysAre() {
        val today = java.time.LocalDate.now().toEpochDay()
        dev.iruki.classtime.util.AppSettings(context).skipOnce(
            dev.iruki.classtime.util.AppSettings.courseSkipKey(7L, today)
        )

        deliver(ScheduleManager.ACTION_START, ScheduleManager.EXTRA_COURSE_ID, 7L)
        assertThat(startedService()).isNull()

        // 다른 과목 행은 그대로 녹음된다.
        deliver(ScheduleManager.ACTION_START, ScheduleManager.EXTRA_COURSE_ID, 8L)
        assertThat(startedService()?.action).isEqualTo(RecordingService.ACTION_START_AUTO)
    }

    /** 회귀: 종료 알람이 녹음을 다시 시작시켜서는 안 된다. */
    @Test
    fun stopAlarm_neverStartsARecording() {
        deliver(ScheduleManager.ACTION_STOP)

        val actions = generateSequence { startedService()?.action }.take(5).toList()
        assertThat(actions).doesNotContain(RecordingService.ACTION_START_AUTO)
        assertThat(actions).doesNotContain(RecordingService.ACTION_START_MAKEUP)
    }
}
