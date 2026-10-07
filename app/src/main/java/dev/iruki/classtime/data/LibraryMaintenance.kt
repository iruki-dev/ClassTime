package dev.iruki.classtime.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingScanner
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.schedule.ScheduleManager
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.util.AppLog
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 폴더 검사 결과. */
data class RescanResult(val added: Int, val removed: Int)

/**
 * 녹음 보관함을 정리하는 일들: 폴더 다시 검사, 녹음으로 시간표 다시 만들기.
 */
@Singleton
class LibraryMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ClassTimeRepository,
    private val storage: RecordingStorage,
    private val scanner: RecordingScanner,
    private val scheduleManager: ScheduleManager,
) {

    // --- 폴더 검사 ---

    suspend fun rescan(): RescanResult = withContext(Dispatchers.IO) {
        val found = scanner.scan()
        val existing = repo.allRecordings()
        val courses = repo.allCourses()
        val plan = RescanPlan.of(existing, found) { subject ->
            courses.firstOrNull { it.subject == subject }?.id
        }
        plan.toAdd.forEach { repo.insertRecording(it) }
        // 목록에는 있는데 폴더에서 안 보인 것: 정말 없는지 하나씩 확인한 뒤에만 지운다.
        val gone = plan.missingCandidates.filter { !storage.exists(Uri.parse(it.uri)) }
        gone.forEach { repo.deleteRecordingRow(it.id) }
        AppLog.i(TAG, "폴더 검사: 추가 ${plan.toAdd.size}, 정리 ${gone.size} (폴더 ${found.size}개)")
        RescanResult(plan.toAdd.size, gone.size)
    }

    // --- 시간표 다시 만들기 ---

    /** 녹음 기록으로 시간표를 짐작한다. 아무것도 바꾸지 않는다. */
    suspend fun inferTimetable(): List<TimetableInference.Result> = withContext(Dispatchers.Default) {
        val zone = ZoneId.systemDefault()
        val samples = repo.allRecordings().filter { !it.ongoing }.map {
            TimetableInference.Sample(
                subject = it.subject,
                start = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDateTime(),
                durationMinutes = (it.durationMs / 60_000).toInt(),
                auto = it.auto,
            )
        }
        val ignore = setOf(
            context.getString(R.string.subject_unknown),
            context.getString(R.string.subject_makeup),
            RecordingStorage.sanitizeSubject(""),
        )
        TimetableInference.infer(samples, ignore)
    }

    /**
     * 짐작한 시간표를 넣는다. 이미 시간표에 있는 과목은 건드리지 않는다(같은 이름이면 건너뜀).
     * @return 새로 넣은 과목 수
     */
    suspend fun applyInferred(results: List<TimetableInference.Result>): Int = withContext(Dispatchers.IO) {
        val existing = repo.allCourses()
        val taken = existing.map { it.subject }.toSet()
        val usedColors = existing.map { it.colorArgb }.toMutableSet()
        var added = 0
        for (r in results) {
            if (r.subject in taken) continue
            val groupId = UUID.randomUUID().toString()
            val color = CourseColors.palette.firstOrNull { it.seed !in usedColors }
                ?: CourseColors.palette[added % CourseColors.palette.size]
            usedColors += color.seed
            r.slots.forEach { slot ->
                repo.upsertCourse(
                    Course(
                        groupId = groupId,
                        subject = r.subject,
                        dayOfWeek = slot.dayOfWeek,
                        startMinute = slot.startMinute,
                        endMinute = slot.endMinute,
                        autoRecord = true,
                        colorArgb = color.seed,
                    )
                )
            }
            added++
        }
        if (added > 0) runCatching { scheduleManager.rescheduleAll() }
            .onFailure { AppLog.e(TAG, "시간표 복원 후 알람 재설정 실패", it) }
        added
    }

    private companion object {
        const val TAG = "LibraryMaintenance"
    }
}
