package dev.iruki.classtime.schedule

import android.app.AlarmManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Course
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 [AlarmManager] 에 알람이 실제로 등록되는지 확인한다.
 *
 * Robolectric 쪽 [ScheduleManagerTest] 는 "우리가 AlarmManager 를 올바르게 호출했다" 까지만
 * 보장한다. 기기에서는 정확 알람 권한·절전 정책 때문에 그 호출이 조용히 거부될 수 있어서,
 * 예외 없이 끝까지 통과하는지를 여기서 본다.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmSchedulingInstrumentedTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var repository: ClassTimeRepository
    @Inject lateinit var scheduleManager: ScheduleManager
    @Inject @ApplicationContext lateinit var context: Context

    private var courseId: Long = 0

    @Before
    fun setUp() {
        hiltRule.inject()
        val tomorrow = LocalDate.now().plusDays(1)
        courseId = runBlocking {
            repository.upsertCourse(
                Course(
                    groupId = "instrumented",
                    subject = "계측테스트과목",
                    dayOfWeek = tomorrow.dayOfWeek.value,
                    startMinute = 10 * 60,
                    endMinute = 11 * 60,
                    autoRecord = true,
                )
            )
        }
    }

    @After
    fun tearDown() = runBlocking {
        scheduleManager.cancelCourse(courseId)
        repository.deleteCourseById(courseId)
    }

    @Test
    fun rescheduleAllCompletesOnRealDevice() = runBlocking {
        // SecurityException 없이 끝나야 한다. 정확 알람이 막혀 있으면
        // setAndAllowWhileIdle 로 대체되도록 되어 있다.
        scheduleManager.rescheduleAll()
    }

    @Test
    fun alarmManagerIsAvailable() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertThat(am).isNotNull()
    }

    @Test
    fun cancellingTwiceIsHarmless() = runBlocking {
        scheduleManager.rescheduleAll()
        scheduleManager.cancelCourse(courseId)
        scheduleManager.cancelCourse(courseId)
    }
}
