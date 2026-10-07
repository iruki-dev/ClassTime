package dev.iruki.classtime.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.iruki.classtime.data.Recording
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex

/**
 * 텍스트 변환 대기열. 화면과 녹음 서비스는 이것만 부른다.
 *
 * 실제 처리는 [TranscriptionWorker] 가 한 번에 하나씩, 들어온 순서대로 한다. 무료 한도는
 * 계정 전체에 걸리므로 동시에 여러 건을 보내 봐야 빨라지지 않고 한도만 먼저 닿는다.
 */
class TranscriptionQueue(
    private val context: Context,
    private val dao: TranscriptDao,
    private val settings: AiSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observe(recordingId: Long): Flow<Transcript?> = dao.observe(recordingId)
    fun observeAll(): Flow<List<Transcript>> = dao.observeAll()

    /**
     * 변환을 맡긴다. 이미 끝난 것도 다시 맡길 수 있다(처음부터).
     * @param correct null 이면 지금 설정을 따른다.
     */
    suspend fun enqueue(recordingId: Long, correct: Boolean? = null) {
        val existing = dao.get(recordingId)
        if (existing != null && existing.stateEnum.active) return
        dao.upsert(
            Transcript(
                recordingId = recordingId,
                queuedAt = clock(),
                correct = correct ?: settings.config.value.canCorrect,
            )
        )
        kick()
    }

    /** 받아 적은 원문은 두고 교정만 다시. */
    suspend fun recorrect(recordingId: Long) {
        val t = dao.get(recordingId) ?: return
        if (t.stateEnum.active || t.segments.isBlank()) return
        dao.upsert(
            t.copy(
                state = TranscriptState.CORRECTING.name,
                queuedAt = clock(),
                correct = true,
                sectionsDone = 0,
                sectionsTotal = TranscriptCorrector.sections(TranscriptJson.segments(t.segments)).size,
                paragraphs = "",
                error = "",
                attempts = 0,
                waitUntil = 0,
            )
        )
        kick()
    }

    /** 대기 중이거나 진행 중인 작업을 멈추고 지운다. 끝난 결과도 이것으로 지운다. */
    suspend fun remove(recordingId: Long) = dao.delete(recordingId)

    /** 실패한 작업을 이어서(끝낸 조각은 그대로). */
    suspend fun retry(recordingId: Long) {
        val t = dao.get(recordingId) ?: return
        if (t.stateEnum != TranscriptState.FAILED) return
        val resume = when {
            t.plan.isBlank() -> TranscriptState.QUEUED
            t.chunksDone < TranscriptJson.plan(t.plan).size -> TranscriptState.TRANSCRIBING
            else -> TranscriptState.TRANSCRIBING // 받아 적기를 마쳤으면 엔진이 교정으로 넘긴다
        }
        dao.upsert(t.copy(state = resume.name, error = "", attempts = 0, waitUntil = 0, queuedAt = clock()))
        kick()
    }

    /** 녹음이 막 끝났을 때. 자동 변환이 켜져 있고 소리가 담긴 녹음만. */
    suspend fun onRecordingFinished(recording: Recording) {
        val config = settings.config.value
        if (!config.enabled || config.groqRejected || !config.autoTranscribe || !settings.consented) return
        if (recording.isSilent || recording.durationMs < MIN_AUTO_MS) return
        enqueue(recording.id)
    }

    /** 처리기를 깨운다. 이미 돌고 있으면 그 뒤에 이어 붙는다(돌던 것이 새 작업도 집어 간다). */
    fun kick() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<TranscriptionWorker>().setConstraints(NETWORK).build(),
        )
    }

    /** 한도 대기 등으로 [delayMs] 뒤에 다시 깨운다. 더 이른 예약이 있으면 그것으로 바꾼다. */
    fun wakeAfter(delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_LATER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setConstraints(NETWORK)
                .setInitialDelay(delayMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    companion object {
        private const val WORK_NOW = "transcribe"
        private const val WORK_LATER = "transcribe_later"
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** 1분보다 짧은 녹음은 자동으로 보내지 않는다(실수로 누른 녹음 등). */
        const val MIN_AUTO_MS = 60_000L

        /** 두 처리기(지금/나중)가 겹쳐 돌지 않게. 하나가 끝나면 다른 하나가 이어 받는다. */
        internal val runLock = Mutex()
    }
}
