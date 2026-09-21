package dev.iruki.classtime.data

import android.net.Uri
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 앱 전역에서 공유하는 데이터 접근 지점. Hilt 가 프로세스당 하나만 만든다.
 *
 * Context 대신 DAO 를 직접 받는다. 그래야 안드로이드 프레임워크 없이도 생성할 수 있어
 * 테스트에서 가짜 DAO 를 끼워 넣기 쉽다.
 */
@Singleton
class ClassTimeRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val recordingDao: RecordingDao,
    private val termDao: TermDao,
    private val exceptionDao: ScheduleExceptionDao,
    private val storage: RecordingStorage,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val healMutex = Mutex()

    val courses: Flow<List<Course>> = courseDao.observeAll()
    val recordings: Flow<List<Recording>> = recordingDao.observeAll()
    val term: Flow<Term?> = termDao.observe()
    val exceptions: Flow<List<ScheduleException>> = exceptionDao.observeAll()

    /**
     * '오늘' 날짜. 자정이 지나면 새 값을 흘려보낸다.
     * 이게 없으면 앱을 자정 너머로 켜둔 동안 '오늘의 수업'이 어제 것으로 굳는다.
     */
    val currentDate: Flow<LocalDate> = flow {
        while (true) {
            val today = LocalDate.now()
            emit(today)
            val nextMidnight = today.plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            delay((nextMidnight - System.currentTimeMillis()).coerceIn(1_000L, 24 * 3600_000L))
        }
    }

    /** 오늘 실제로 열리는 수업(정규 + 보강, 휴강/학기밖 반영). */
    val todaySessions: Flow<List<Session>> =
        combine(courses, exceptions, term, currentDate) { c, e, t, date ->
            CourseMatching.sessionsOn(date, c, e, t)
        }

    /** 서비스가 갱신하고 UI 가 관찰하는 현재 녹음 상태. */
    private val _status = MutableStateFlow(RecordingStatus.Idle)
    val status = _status.asStateFlow()

    /** 대기 모드 상태. 자동 녹음에 소리가 들어올지 여부가 여기에 달려 있다. */
    private val _standby = MutableStateFlow(StandbyState())
    val standby = _standby.asStateFlow()

    fun updateStandby(state: StandbyState) {
        _standby.value = state
    }

    fun updateStatus(status: RecordingStatus) {
        _status.value = status
        // 녹음이 끝났다는 신호가 오면, 서비스가 중간에 죽어서 '녹음 중'으로 남은 행이 없는지
        // 확인하고 마무리한다. (홈 탭은 종료로 보이는데 목록은 '녹음 중'이던 문제의 해결책)
        if (!status.active) scope.launch {
            runCatching { healStaleRecordings() }
                .onFailure { AppLog.e(TAG, "녹음 종료 후 정리 실패", it) }
        }
    }

    // --- Course ---
    suspend fun allCourses(): List<Course> = courseDao.getAll()
    suspend fun course(id: Long): Course? = courseDao.getById(id)
    suspend fun coursesInGroup(groupId: String): List<Course> = courseDao.getByGroup(groupId)
    suspend fun upsertCourse(course: Course): Long = courseDao.upsert(course)
    suspend fun deleteCourse(course: Course) = courseDao.delete(course)
    suspend fun deleteCourseById(id: Long) = courseDao.deleteById(id)
    suspend fun deleteCourseGroup(groupId: String) = courseDao.deleteByGroup(groupId)

    // --- Term ---
    suspend fun termOnce(): Term? = termDao.get()
    suspend fun upsertTerm(term: Term) = termDao.upsert(term)

    // --- Exceptions ---
    suspend fun allExceptions(): List<ScheduleException> = exceptionDao.getAll()
    suspend fun exception(id: Long): ScheduleException? = exceptionDao.getById(id)
    suspend fun upsertException(e: ScheduleException): Long = exceptionDao.upsert(e)
    suspend fun deleteException(e: ScheduleException) = exceptionDao.delete(e)
    suspend fun pruneOldExceptions() =
        exceptionDao.deleteBefore(LocalDate.now().toEpochDay())

    // --- Sessions (규칙 적용된 "실제 수업") ---
    suspend fun sessionsOn(date: LocalDate = LocalDate.now()): List<Session> =
        CourseMatching.sessionsOn(date, courseDao.getAll(), exceptionDao.getAll(), termDao.get())

    /** 수동 녹음의 자동 라벨링용. */
    suspend fun currentSession(
        date: LocalDate = LocalDate.now(),
        minuteOfDay: Int = TimeUtils.nowMinuteOfDay(),
    ): Session? = CourseMatching.currentSession(sessionsOn(date), minuteOfDay)

    /** 놓친 자동 녹음 이어서 시작용(종료 시각 지난 수업 제외). */
    suspend fun sessionInProgress(
        date: LocalDate = LocalDate.now(),
        minuteOfDay: Int = TimeUtils.nowMinuteOfDay(),
    ): Session? = CourseMatching.sessionInProgress(sessionsOn(date), minuteOfDay)

    // --- Recording ---
    suspend fun ongoingRecording(): Recording? = recordingDao.getOngoing()
    suspend fun recording(id: Long): Recording? = recordingDao.getById(id)
    suspend fun insertRecording(recording: Recording): Long = recordingDao.insert(recording)
    suspend fun updateRecording(recording: Recording) = recordingDao.update(recording)
    suspend fun deleteRecording(recording: Recording) = recordingDao.delete(recording)
    suspend fun deleteRecordingRow(id: Long) = recordingDao.deleteById(id)

    /**
     * '녹음 중'으로 남은 행을 실제로 마무리한다: MediaStore pending 해제(→ 재생 가능),
     * 길이·용량을 파일에서 다시 읽어 채우고 ongoing=false 로 바꾼다.
     * 앱 시작 시, 그리고 녹음 종료 신호가 올 때마다 호출된다. 재호출해도 안전(idempotent).
     */
    suspend fun healStaleRecordings() = healMutex.withLock {
        healOngoingRecordings(recordingDao, storage) { _status.value.active }
    }

    /** 재생 실패 등으로 목록의 한 건을 강제로 마무리하고 갱신된 행을 돌려준다. */
    suspend fun forceFinalize(recording: Recording): Recording =
        finalizeRecordingRow(recordingDao, storage, recording)

    private companion object {
        const val TAG = "ClassTimeRepository"
    }
}

/**
 * 마무리를 놓친(ongoing=true) 녹음 행들을 재생 가능한 완료본으로 되돌린다.
 * 저장소·상태 소스에 의존하지 않는 순수 로직이라 테스트로 그대로 검증한다.
 */
suspend fun healOngoingRecordings(
    dao: RecordingDao,
    storage: RecordingStorage,
    isRecordingActive: () -> Boolean,
) {
    if (isRecordingActive()) return
    for (row in dao.getAllOngoing()) {
        finalizeRecordingRow(dao, storage, row)
    }
}

/** 한 행의 파일을 확정(pending 해제)하고 길이·용량을 채워 ongoing=false 로 만든다. idempotent. */
suspend fun finalizeRecordingRow(
    dao: RecordingDao,
    storage: RecordingStorage,
    row: Recording,
): Recording {
    val uri = Uri.parse(row.uri)
    val size = runCatching { storage.finalize(uri, null) }.getOrDefault(row.sizeBytes)
    val duration = row.durationMs.takeIf { it > 0 }
        ?: runCatching { storage.probeDurationMs(uri) }.getOrDefault(0L)
    val fixed = row.copy(ongoing = false, sizeBytes = size, durationMs = duration)
    if (fixed != row) dao.update(fixed)
    return fixed
}

/**
 * 대기 모드 스냅샷.
 *
 * @param active 녹음 서비스가 포그라운드로 살아 있는지.
 * @param micReady 그 포그라운드 전환이 **앱이 화면에 있을 때** 이뤄졌는지.
 *   안드로이드는 서비스가 포그라운드가 되는 그 순간의 앱 상태로 마이크 접근 권한을 고정한다.
 *   false 면 서비스는 살아 있어도 마이크가 막혀 무음이 녹음된다.
 */
data class StandbyState(
    val active: Boolean = false,
    val micReady: Boolean = false,
) {
    /**
     * 지금 수업 알람이 울리면 **소리가 담긴** 녹음이 될지.
     * 서비스가 떠 있기만 하고 마이크를 못 쥔 상태는 무음 녹음과 같으므로 준비된 것이 아니다.
     */
    val readyForSilentFreeRecording get() = active && micReady
}

/** UI 표시용 녹음 상태 스냅샷. */
data class RecordingStatus(
    val active: Boolean,
    val subject: String = "",
    val auto: Boolean = false,
    val startedAt: Long = 0L,
) {
    companion object {
        val Idle = RecordingStatus(active = false)
    }
}
