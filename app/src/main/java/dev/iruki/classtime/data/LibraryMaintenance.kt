package dev.iruki.classtime.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.AudioCompressor
import dev.iruki.classtime.audio.PlaybackController
import dev.iruki.classtime.audio.RecordingScanner
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.di.ApplicationScope
import dev.iruki.classtime.schedule.ScheduleManager
import dev.iruki.classtime.ui.theme.CourseColors
import dev.iruki.classtime.util.AppLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 압축 진행 상황. */
data class CompressionState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** 지금 파일의 진행(0..1). */
    val fileProgress: Float = 0f,
    val savedBytes: Long = 0L,
    val failed: Int = 0,
)

/** 폴더 검사 결과. */
data class RescanResult(val added: Int, val removed: Int)

/**
 * 녹음 보관함을 정리하는 일들: 폴더 다시 검사, 녹음으로 시간표 다시 만들기, 압축.
 * 셋 다 화면을 떠나도 끝까지 가야 하므로 앱 스코프에서 돈다.
 */
@Singleton
class LibraryMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ClassTimeRepository,
    private val storage: RecordingStorage,
    private val scanner: RecordingScanner,
    private val compressor: AudioCompressor,
    private val playback: PlaybackController,
    private val scheduleManager: ScheduleManager,
    @ApplicationScope private val scope: CoroutineScope,
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

    // --- 압축 ---

    private val _compression = MutableStateFlow(CompressionState())
    val compression = _compression.asStateFlow()

    /** 압축할 수 있는 녹음: 끝났고, 아직 원본이고, 파일이 있는 것. */
    fun compressible(list: List<Recording>): List<Recording> =
        list.filter { !it.ongoing && !it.compressed && it.sizeBytes > 0 && it.durationMs > 0 }

    /** 압축하면 줄어드는 대략의 크기. */
    fun estimatedSavings(list: List<Recording>): Long =
        compressible(list).sumOf { (it.sizeBytes * (1 - AudioCompressor.SIZE_RATIO)).toLong() }

    /** 차례로 압축한다. 이미 돌고 있으면 무시한다. */
    fun compress(recordings: List<Recording>) {
        if (_compression.value.running) return
        val targets = compressible(recordings)
        if (targets.isEmpty()) return
        _compression.value = CompressionState(running = true, total = targets.size)
        scope.launch {
            var saved = 0L
            var failed = 0
            targets.forEachIndexed { i, rec ->
                _compression.value = _compression.value.copy(done = i, fileProgress = 0f)
                val result = runCatching { compressOne(rec) }
                    .onFailure { AppLog.e(TAG, "압축 실패: ${rec.fileName}", it) }
                    .getOrNull()
                if (result == null) failed++ else saved += result
                _compression.value = _compression.value.copy(savedBytes = saved, failed = failed)
            }
            _compression.value = _compression.value.copy(running = false, done = targets.size, fileProgress = 1f)
        }
    }

    fun dismissCompressionResult() {
        if (!_compression.value.running) _compression.value = CompressionState()
    }

    /** @return 줄어든 바이트. 이득이 없으면 원본을 그대로 두고 0. */
    private suspend fun compressOne(rec: Recording): Long {
        // 녹음이 시작돼 그 사이 ongoing 이 됐거나 다른 곳에서 이미 압축됐을 수 있다.
        val fresh = repo.recording(rec.id) ?: return 0L
        if (fresh.ongoing || fresh.compressed) return 0L
        withContext(Dispatchers.Main) { playback.releaseIfPlaying(fresh.id) }

        val uri = Uri.parse(fresh.uri)
        val tmp = File(context.cacheDir, "compress-${fresh.id}.m4a")
        try {
            val outMs = compressor.compress(uri, tmp) { p ->
                _compression.value = _compression.value.copy(fileProgress = p)
            }
            // 검증: 길이가 원본과 거의 같아야 하고(2초 또는 2% 이내), 실제로 작아야 한다.
            val tolerance = maxOf(2_000L, fresh.durationMs / 50)
            val probed = storage.probeDurationMs(Uri.fromFile(tmp)).takeIf { it > 0 } ?: outMs
            check(abs(probed - fresh.durationMs) <= tolerance) {
                "압축본 길이가 다릅니다: 원본 ${fresh.durationMs}ms, 압축본 ${probed}ms"
            }
            if (tmp.length() >= fresh.sizeBytes * 9 / 10) {
                // 줄어들지 않으면 원본을 지킨다. 다시 시도하지 않도록 표시만 한다.
                repo.updateRecording(fresh.copy(compressed = true))
                return 0L
            }
            // 같은 자리에 덮어쓴다. 파일 이름·위치·uri 가 그대로라 목록과 PC 에서 보이는 모습이 같다.
            // 'wt' 는 먼저 비우고 쓰므로, 저장 공간이 거의 없어도 더 작은 압축본은 들어간다.
            context.contentResolver.openOutputStream(uri, "wt").use { out ->
                checkNotNull(out) { "원본 파일을 열 수 없습니다" }
                tmp.inputStream().use { it.copyTo(out) }
            }
            val newSize = storage.querySize(uri).takeIf { it > 0 } ?: tmp.length()
            repo.updateRecording(fresh.copy(compressed = true, sizeBytes = newSize, durationMs = probed))
            return (fresh.sizeBytes - newSize).coerceAtLeast(0)
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "LibraryMaintenance"
    }
}
