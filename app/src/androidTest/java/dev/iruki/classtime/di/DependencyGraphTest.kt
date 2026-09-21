package dev.iruki.classtime.di

import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.schedule.ScheduleManager
import dev.iruki.classtime.util.AppSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 의존성 그래프가 **실기기에서** 끝까지 조립되는지 확인한다.
 *
 * Hilt 의 설정 오류 상당수는 컴파일은 통과하고 런타임에야 터진다. 그리고 이 앱에서
 * 그래프를 처음 쓰는 곳은 보통 부팅 직후의 BroadcastReceiver 라, 문제가 생기면
 * 사용자는 "어느 날부터 녹음이 안 됨" 으로만 겪는다. 그 전에 여기서 걸러 낸다.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DependencyGraphTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var database: AppDatabase
    @Inject lateinit var repository: ClassTimeRepository
    @Inject lateinit var scheduleManager: ScheduleManager
    @Inject lateinit var storage: RecordingStorage
    @Inject lateinit var settings: AppSettings
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    @Before
    fun setUp() = hiltRule.inject()

    @Test
    fun everyInjectedDependencyResolves() {
        assertThat(database).isNotNull()
        assertThat(repository).isNotNull()
        assertThat(scheduleManager).isNotNull()
        assertThat(storage).isNotNull()
        assertThat(settings).isNotNull()
        assertThat(applicationScope).isNotNull()
    }

    @Test
    fun databaseOpensAndReportsTheExpectedVersion() {
        assertThat(database.openHelper.readableDatabase.version).isEqualTo(3)
    }

    @Test
    fun applicationScopeIsStillActive() {
        assertThat(applicationScope.coroutineContext[kotlinx.coroutines.Job]?.isActive).isTrue()
    }
}
