package dev.iruki.classtime.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.data.RecordingDao
import dev.iruki.classtime.data.Summary
import dev.iruki.classtime.data.SummaryDao
import dev.iruki.classtime.data.SummaryError
import dev.iruki.classtime.data.SummaryState
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.util.AppLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 강의 요약 처리. 기다리는 요약을 배치 하나로 보내고, 보낸 배치가 끝났는지 확인해 결과를 저장한다.
 * 한 번 돌 때마다 DB 에 남기므로 앱이 꺼져도 이어서 확인한다.
 */
class SummaryEngine(
    private val transcripts: TranscriptDao,
    private val summaries: SummaryDao,
    private val recordings: RecordingDao,
    private val settings: AiSettings,
    private val files: TextFiles,
    private val api: SummaryApi = ClaudeClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Pass(
        /** 다음에 깨울 시각(epoch ms). 없으면 기다릴 것이 없다. */
        val nextAt: Long?,
        /** 이번에 끝난(성공·실패) 녹음. */
        val finished: List<Long>,
        /** 연결이 없어 아무것도 못 했다. 연결되면 다시. */
        val offline: Boolean = false,
    )

    suspend fun run(): Pass = withContext(Dispatchers.IO) {
        val pending = summaries.pending()
        if (pending.isEmpty()) return@withContext Pass(null, emptyList())
        val key = settings.claudeKey()
        if (key == null) {
            pending.forEach { fail(it, SummaryError.CLAUDE_KEY) }
            return@withContext Pass(null, pending.map { it.recordingId })
        }
        val finished = mutableListOf<Long>()
        try {
            submitQueued(key, pending.filter { it.stateEnum == SummaryState.QUEUED }, finished)
            checkSubmitted(key, finished)
        } catch (e: SummaryApiException) {
            when (e.kind) {
                SummaryApiException.Kind.NETWORK -> return@withContext Pass(null, finished, offline = true)
                SummaryApiException.Kind.AUTH -> {
                    settings.markClaudeRejected()
                    summaries.pending().forEach { fail(it, SummaryError.CLAUDE_KEY); finished += it.recordingId }
                }
                SummaryApiException.Kind.BILLING -> {
                    summaries.pending().filter { it.stateEnum == SummaryState.QUEUED }
                        .forEach { fail(it, SummaryError.BILLING); finished += it.recordingId }
                }
                SummaryApiException.Kind.RATE_LIMIT, SummaryApiException.Kind.SERVER ->
                    return@withContext Pass(clock() + RETRY_MS, finished)
                SummaryApiException.Kind.BAD_REQUEST -> {
                    AppLog.w(TAG, "요약 요청 거부", e)
                    summaries.pending().filter { it.stateEnum == SummaryState.QUEUED }
                        .forEach { fail(it, SummaryError.UNKNOWN, e.message.orEmpty()); finished += it.recordingId }
                }
            }
        }
        Pass(summaries.pending().minOfOrNull { if (it.stateEnum == SummaryState.QUEUED) clock() else it.checkAt }, finished)
    }

    /** 기다리는 요약을 모두 배치 하나로 보낸다. */
    private suspend fun submitQueued(key: String, queued: List<Summary>, finished: MutableList<Long>) {
        val ready = mutableListOf<Pair<Summary, SummaryRequest>>()
        for (s in queued) {
            val t = transcripts.get(s.recordingId)
            val rec = recordings.getById(s.recordingId)
            val paragraphs = t?.takeIf { it.stateEnum == TranscriptState.DONE }?.let { TranscriptJson.paragraphs(it.paragraphs) }.orEmpty()
            if (rec == null || paragraphs.isEmpty()) {
                fail(s, SummaryError.NO_TEXT)
                finished += s.recordingId
                continue
            }
            ready += s.copy(transcriptAt = t!!.updatedAt) to
                SummaryRequest(customId(s.recordingId), s.model, SummaryPrompt.SYSTEM, SummaryPrompt.user(rec, paragraphs))
        }
        if (ready.isEmpty()) return
        val batchId = api.submit(key, ready.map { it.second })
        val now = clock()
        for ((s, _) in ready) {
            // 그사이 취소됐으면 되살리지 않는다(배치는 이미 보냈으니 결과는 버린다).
            if (summaries.get(s.recordingId) == null) continue
            summaries.upsert(
                s.copy(state = SummaryState.SUBMITTED.name, batchId = batchId, checkAt = now + interval(0), checks = 0, updatedAt = now, error = "")
            )
        }
    }

    /** 확인할 때가 된 배치를 확인하고, 끝났으면 결과를 저장한다. */
    private suspend fun checkSubmitted(key: String, finished: MutableList<Long>) {
        val now = clock()
        val due = summaries.pending().filter { it.stateEnum == SummaryState.SUBMITTED && it.checkAt <= now }
        for ((batchId, group) in due.groupBy { it.batchId }) {
            if (!api.ended(key, batchId)) {
                group.forEach { summaries.upsert(it.copy(checkAt = now + interval(it.checks + 1), checks = it.checks + 1, updatedAt = now)) }
                continue
            }
            val results = api.results(key, batchId)
            for (s in group) {
                val latest = summaries.get(s.recordingId) ?: continue
                if (latest.batchId != batchId) continue // 그사이 다시 맡김
                finish(latest, results[customId(s.recordingId)])
                finished += s.recordingId
            }
        }
    }

    private suspend fun finish(s: Summary, outcome: SummaryOutcome?) {
        when (outcome) {
            is SummaryOutcome.Success -> {
                val t = transcripts.get(s.recordingId)
                val paragraphs = t?.let { TranscriptJson.paragraphs(it.paragraphs) }.orEmpty()
                val parsed = runCatching { SummaryJson.parse(outcome.text, paragraphs.size) }.getOrNull()
                if (parsed == null) {
                    fail(s, SummaryError.UNKNOWN, "invalid JSON", outcome.inputTokens, outcome.outputTokens)
                    return
                }
                summaries.upsert(
                    s.copy(
                        state = SummaryState.DONE.name, content = SummaryJson.encode(parsed),
                        inputTokens = outcome.inputTokens, outputTokens = outcome.outputTokens,
                        updatedAt = clock(), error = "",
                    )
                )
                // 녹음 옆(Documents/ClassTime/<과목>)에 요약 파일로도 남긴다. 못 써도 요약은 끝난 것으로.
                recordings.getById(s.recordingId)?.let { rec ->
                    runCatching { files.write(rec, TextKind.SUMMARY, SummaryJson.format(rec, parsed) { paragraphs.getOrNull(it)?.startMs }) }
                        .onFailure { AppLog.w(TAG, "요약 파일을 쓰지 못했습니다 id=${s.recordingId}", it) }
                }
            }
            is SummaryOutcome.Truncated -> fail(s, SummaryError.TOO_LONG, "", outcome.inputTokens, outcome.outputTokens)
            is SummaryOutcome.Refused -> fail(s, SummaryError.REFUSED, outcome.category)
            is SummaryOutcome.Errored -> fail(s, if (outcome.billing) SummaryError.BILLING else SummaryError.SERVER, outcome.message)
            SummaryOutcome.Expired -> fail(s, SummaryError.EXPIRED)
            SummaryOutcome.Canceled, null -> fail(s, SummaryError.UNKNOWN)
        }
    }

    private suspend fun fail(s: Summary, error: SummaryError, detail: String = "", input: Long = s.inputTokens, output: Long = s.outputTokens) {
        if (summaries.get(s.recordingId) == null) return
        summaries.upsert(
            s.copy(state = SummaryState.FAILED.name, error = SummaryError.encode(error, detail), inputTokens = input, outputTokens = output, updatedAt = clock())
        )
    }

    companion object {
        private const val TAG = "Summary"
        private const val RETRY_MS = 2 * 60_000L

        fun customId(recordingId: Long) = "rec-$recordingId"

        /**
         * n 번째 확인까지 기다리는 시간. 배치는 대개 몇 분, 길어도 한 시간 안에 끝나므로
         * 처음에는 자주, 그다음은 드물게(최대 15분) 본다.
         */
        fun interval(checks: Int): Long = when (checks) {
            0 -> 60_000L
            1 -> 2 * 60_000L
            2 -> 5 * 60_000L
            3 -> 10 * 60_000L
            else -> 15 * 60_000L
        }
    }
}

/** 요약 대기열. 화면과 변환 처리기는 이것만 부른다. */
class SummaryQueue(
    private val context: Context,
    private val dao: SummaryDao,
    private val transcripts: TranscriptDao,
    private val recordings: RecordingDao,
    private val settings: AiSettings,
    private val files: TextFiles,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(recordingId: Long): Flow<Summary?> = dao.observe(recordingId)
    fun observeAll(): Flow<List<Summary>> = dao.observeAll()

    /** 요약을 맡긴다. 끝난 것도 다시(처음부터). 텍스트 변환이 끝난 녹음만. */
    suspend fun enqueue(recordingId: Long, model: SummaryModel = settings.config.value.summaryModel): Boolean {
        val t = transcripts.get(recordingId)?.takeIf { it.stateEnum == TranscriptState.DONE } ?: return false
        val existing = dao.get(recordingId)
        if (existing != null && existing.stateEnum.active) return true
        dao.upsert(Summary(recordingId = recordingId, model = model.id, queuedAt = clock(), transcriptAt = t.updatedAt))
        kick()
        return true
    }

    /** 요약과 그 파일을 지운다. 보낸 배치의 결과는 와도 버린다. */
    suspend fun remove(recordingId: Long) {
        dao.delete(recordingId)
        recordings.getById(recordingId)?.let { runCatching { files.delete(it, TextKind.SUMMARY) } }
    }

    /** 텍스트 변환이 막 끝났을 때. 자동 요약이 켜져 있으면 맡긴다. */
    suspend fun onTranscribed(recordingId: Long) {
        val c = settings.config.value
        if (!c.summaryEnabled || c.claudeRejected || !c.autoSummarize || !settings.summaryConsented) return
        enqueue(recordingId)
    }

    /** 처리기를 깨운다. 이미 돌고 있으면 그 뒤에 이어 붙는다. */
    fun kick() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SummaryWorker>().setConstraints(NETWORK).build(),
        )
    }

    /** 배치를 다시 확인할 때 깨운다. 더 이른 예약이 있으면 이것으로 바꾼다. */
    fun wakeAfter(delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_LATER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SummaryWorker>()
                .setConstraints(NETWORK)
                .setInitialDelay(delayMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    companion object {
        private const val WORK_NOW = "summarize"
        private const val WORK_LATER = "summarize_later"
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        internal val runLock = Mutex()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SummaryEntryPoint {
    fun summaryEngine(): SummaryEngine
    fun summaryQueue(): SummaryQueue
    fun summaries(): SummaryDao
    fun recordings(): RecordingDao
    fun settings(): AiSettings
}

/** 요약 처리기. 한 번 돌고, 기다릴 배치가 있으면 그때 다시 깨도록 예약하고 끝난다. */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, SummaryEntryPoint::class.java)

    override suspend fun doWork(): Result = SummaryQueue.runLock.withLock {
        deps.summaries().deleteOrphans()
        // ‘사용’ 스위치를 끄면 대기열은 그대로 두고 멈춘다. 다시 켜면 이어서.
        if (!deps.settings().config.value.switchOn) return@withLock Result.success()
        val pass = runCatching { deps.summaryEngine().run() }
            .getOrElse {
                AppLog.w(TAG, "요약 처리 중 예외", it)
                return@withLock Result.retry()
            }
        notifyFinished(pass.finished)
        if (pass.offline) return@withLock Result.retry()
        pass.nextAt?.let { deps.summaryQueue().wakeAfter(it - System.currentTimeMillis()) }
        Result.success()
    }

    private suspend fun notifyFinished(ids: List<Long>) {
        for (id in ids) {
            val s = deps.summaries().get(id) ?: continue
            val rec = deps.recordings().getById(id) ?: continue
            val ok = s.stateEnum == SummaryState.DONE
            val n = NotificationCompat.Builder(applicationContext, ClassTimeApp.CHANNEL_TRANSCRIPTION)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(rec.subject)
                .setContentText(applicationContext.getString(if (ok) R.string.summary_notif_done else R.string.summary_notif_failed))
                .setContentIntent(
                    PendingIntent.getActivity(
                        applicationContext,
                        SUMMARY_ID_BASE + id.toInt(),
                        Intent(applicationContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(TranscriptionWorker.EXTRA_RECORDING_ID, id)
                            .putExtra(EXTRA_OPEN_SUMMARY, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setAutoCancel(true)
                .build()
            runCatching { ClassTimeApp.notificationManager(applicationContext).notify(SUMMARY_ID_BASE + id.toInt(), n) }
        }
    }

    companion object {
        private const val TAG = "SummaryWorker"
        private const val SUMMARY_ID_BASE = 4_600
        /** 알림을 누르면 요약 탭으로 연다. */
        const val EXTRA_OPEN_SUMMARY = "dev.iruki.classtime.extra.OPEN_SUMMARY"
    }
}
