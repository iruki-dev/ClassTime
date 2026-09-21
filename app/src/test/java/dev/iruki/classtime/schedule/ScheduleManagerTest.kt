package dev.iruki.classtime.schedule

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Course
import dev.iruki.classtime.data.ScheduleException
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.RecordingStatus
import dev.iruki.classtime.data.Term
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 자동 녹음의 심장부. 알람이 제때 걸리지 않으면 앱의 존재 이유가 사라지는데,
 * 지금까지 이 경로에는 테스트가 없었다.
 *
 * 내일 요일을 기준으로 수업을 만든다. "지금"이 몇 시든 내일 그 시각은 반드시 미래이므로
 * 테스트가 실행 시각에 따라 흔들리지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ClassTimeRepository
    private lateinit var manager: ScheduleManager
    private lateinit var alarmManager: AlarmManager

    private val tomorrow: LocalDate = LocalDate.now().plusDays(1)
    private val tomorrowDow: Int = tomorrow.dayOfWeek.value

    private val startMinute = 10 * 60      // 10:00
    private val endMinute = 11 * 60 + 30   // 11:30

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        // Hilt 리팩토링 덕분에 리포지토리를 DAO 만으로 조립할 수 있다.
        repo = ClassTimeRepository(
            courseDao = db.courseDao(),
            recordingDao = db.recordingDao(),
            termDao = db.termDao(),
            exceptionDao = db.scheduleExceptionDao(),
            storage = RecordingStorage(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        manager = ScheduleManager(context, repo)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    @After
    fun tearDown() = db.close()

    private fun scheduledTriggerTimes(): List<Long> =
        shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtTime }.sorted()

    private suspend fun addAutoCourse(auto: Boolean = true, group: String = "g1") =
        repo.upsertCourse(
            Course(
                groupId = group,
                subject = "자료구조",
                dayOfWeek = tomorrowDow,
                startMinute = startMinute,
                endMinute = endMinute,
                autoRecord = auto,
            )
        )

    @Test
    fun autoCourse_armsStartAndStopAlarmsAtExactTimes() = runTest {
        addAutoCourse()

        manager.rescheduleAll()

        assertThat(scheduledTriggerTimes()).containsExactly(
            TimeUtils.millisAt(tomorrow, startMinute),
            TimeUtils.millisAt(tomorrow, endMinute),
        )
    }

    @Test
    fun courseWithAutoRecordOff_armsNothing() = runTest {
        addAutoCourse(auto = false)

        manager.rescheduleAll()

        assertThat(scheduledTriggerTimes()).isEmpty()
    }

    /**
     * 재설정은 **절대 녹음을 시작하지 않는다.** 예전에는 종료 알람이 재설정을 부르고,
     * 재설정이 다시 녹음을 시작해서 방금 끝난 수업이 무한히 재녹음되는 버그가 있었다.
     */
    @Test
    fun rescheduleAll_neverStartsAService() = runTest {
        addAutoCourse()

        manager.rescheduleAll()

        assertThat(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedService).isNull()
    }

    @Test
    fun cancelledClass_skipsToTheFollowingWeek() = runTest {
        addAutoCourse(group = "g1")
        repo.upsertException(ScheduleException.cancel(tomorrow, courseGroupId = "g1"))

        manager.rescheduleAll()

        val nextWeek = tomorrow.plusWeeks(1)
        assertThat(scheduledTriggerTimes()).containsExactly(
            TimeUtils.millisAt(nextWeek, startMinute),
            TimeUtils.millisAt(nextWeek, endMinute),
        )
    }

    @Test
    fun holidayCancelsEveryCourseThatDay() = runTest {
        addAutoCourse(group = "g1")
        // courseGroupId = null 이면 그날 전체 휴강(공휴일).
        repo.upsertException(ScheduleException.cancel(tomorrow, courseGroupId = null))

        manager.rescheduleAll()

        val nextWeek = tomorrow.plusWeeks(1)
        assertThat(scheduledTriggerTimes())
            .containsExactly(
                TimeUtils.millisAt(nextWeek, startMinute),
                TimeUtils.millisAt(nextWeek, endMinute),
            )
    }

    @Test
    fun outsideTerm_armsNothing() = runTest {
        addAutoCourse()
        // 학기가 이미 끝난 상태.
        repo.upsertTerm(
            Term(
                startEpochDay = LocalDate.now().minusDays(60).toEpochDay(),
                endEpochDay = LocalDate.now().minusDays(1).toEpochDay(),
            )
        )

        manager.rescheduleAll()

        assertThat(scheduledTriggerTimes()).isEmpty()
    }

    @Test
    fun makeupClass_armsAlarms() = runTest {
        repo.upsertException(
            ScheduleException.makeup(
                date = tomorrow,
                subject = "보강수업",
                startMinute = 14 * 60,
                endMinute = 15 * 60,
            )
        )

        manager.rescheduleAll()

        assertThat(scheduledTriggerTimes()).containsExactly(
            TimeUtils.millisAt(tomorrow, 14 * 60),
            TimeUtils.millisAt(tomorrow, 15 * 60),
        )
    }

    @Test
    fun makeupWithAutoRecordOff_armsNothing() = runTest {
        repo.upsertException(
            ScheduleException.makeup(
                date = tomorrow,
                subject = "보강수업",
                startMinute = 14 * 60,
                endMinute = 15 * 60,
                autoRecord = false,
            )
        )

        manager.rescheduleAll()

        assertThat(scheduledTriggerTimes()).isEmpty()
    }

    @Test
    fun rescheduleAll_isIdempotent() = runTest {
        addAutoCourse()

        manager.rescheduleAll()
        val first = scheduledTriggerTimes()
        manager.rescheduleAll()

        // 같은 PendingIntent 를 재사용하므로 두 번 돌려도 알람이 늘지 않아야 한다.
        assertThat(scheduledTriggerTimes()).isEqualTo(first)
    }

    // --- catchUpIfMidClass: 앱 실행/부팅 시 놓친 녹음 따라잡기 ---

    /** 지금 진행 중인 수업을 만든다. 자정 근처에서도 항상 "진행 중"이 되도록 폭을 잡는다. */
    private suspend fun addCourseInProgressNow(): Long {
        val now = TimeUtils.nowMinuteOfDay()
        val start = (now - 10).coerceAtLeast(0)
        val end = (now + 50).coerceAtMost(24 * 60 - 1)
        return repo.upsertCourse(
            Course(
                groupId = "now",
                subject = "진행중수업",
                dayOfWeek = LocalDate.now().dayOfWeek.value,
                startMinute = start,
                endMinute = end,
                autoRecord = true,
            )
        )
    }

    private fun grantMic() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun startedServiceAction(): String? =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedService?.action

    @Test
    fun catchUp_startsRecordingWhenAClassIsAlreadyUnderway() = runTest {
        grantMic()
        addCourseInProgressNow()

        manager.catchUpIfMidClass()

        assertThat(startedServiceAction()).isEqualTo(RecordingService.ACTION_START_AUTO)
    }

    @Test
    fun catchUp_doesNothingOutsideClassHours() = runTest {
        grantMic()
        addAutoCourse() // 내일 수업이라 지금은 진행 중이 아니다.

        manager.catchUpIfMidClass()

        assertThat(startedServiceAction()).isNull()
    }

    @Test
    fun catchUp_doesNothingWithoutMicPermission() = runTest {
        // 마이크 권한을 주지 않는다.
        addCourseInProgressNow()

        manager.catchUpIfMidClass()

        assertThat(startedServiceAction()).isNull()
    }

    /**
     * 이미 녹음 중이면 다시 시작하지 않는다. 상태가 active 여야 정리 로직이 그 행을
     * 좀비로 보고 치워 버리지 않는다.
     */
    @Test
    fun catchUp_doesNotStartASecondRecording() = runTest {
        grantMic()
        addCourseInProgressNow()
        repo.updateStatus(RecordingStatus(active = true, subject = "진행중수업"))
        repo.insertRecording(
            Recording(
                subject = "진행중수업",
                fileName = "x.m4a",
                uri = "content://fake/1",
                relativePath = "Music/ClassTime",
                startedAt = System.currentTimeMillis(),
                ongoing = true,
            )
        )

        manager.catchUpIfMidClass()

        assertThat(startedServiceAction()).isNull()
    }

    @Test
    fun cancelCourse_removesItsAlarms() = runTest {
        val id = addAutoCourse()
        manager.rescheduleAll()
        assertThat(scheduledTriggerTimes()).hasSize(2)

        manager.cancelCourse(id)

        assertThat(scheduledTriggerTimes()).isEmpty()
    }
}
