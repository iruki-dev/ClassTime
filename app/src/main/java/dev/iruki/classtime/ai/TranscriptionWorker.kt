package dev.iruki.classtime.ai

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.iruki.classtime.ClassTimeApp
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.data.RecordingDao
import dev.iruki.classtime.data.Transcript
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.data.TranscriptError
import dev.iruki.classtime.data.TranscriptState
import dev.iruki.classtime.util.AppLog
import kotlinx.coroutines.sync.withLock

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TranscriptionEntryPoint {
    fun engine(): TranscriptionEngine
    fun transcripts(): TranscriptDao
    fun recordings(): RecordingDao
    fun queue(): TranscriptionQueue
    fun settings(): AiSettings
}

/**
 * 대기열 처리기. 남은 작업이 없거나 모두 기다리는 중이 될 때까지 걸음을 반복한다.
 *
 * 한 건이 수십 분 걸릴 수 있어 진행 알림과 함께 포그라운드로 돈다. 백그라운드 제한으로
 * 포그라운드가 허락되지 않으면 그냥 돌다가, 시스템이 멈추면 저장된 걸음부터 다시 한다.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, TranscriptionEntryPoint::class.java)

    override suspend fun doWork(): Result = TranscriptionQueue.runLock.withLock {
        val dao = deps.transcripts()
        dao.deleteOrphans()
        val finishedBefore = dao.pending().map { it.recordingId }.toSet()

        while (!isStopped) {
            // ‘사용’ 스위치를 끄면 대기열은 그대로 두고 멈춘다. 다시 켜면 이어서.
            if (!deps.settings().config.value.enabled) break
            val pending = dao.pending()
            if (pending.isEmpty()) break
            val now = System.currentTimeMillis()
            val job = pending.firstOrNull { it.waitUntil <= now }
            if (job == null) {
                deps.queue().wakeAfter(pending.minOf { it.waitUntil } - now)
                break
            }
            runCatching { setForeground(foregroundInfo(job)) }
                .onFailure { AppLog.w(TAG, "포그라운드로 전환하지 못했습니다", it) }

            when (deps.engine().step(job) { isStopped }) {
                TranscriptionEngine.Outcome.Offline -> {
                    notifyResults(finishedBefore)
                    return@withLock Result.retry()
                }
                TranscriptionEngine.Outcome.Progressed -> Unit
            }
        }
        notifyResults(finishedBefore)
        Result.success()
    }

    /** 이번에 끝난 작업마다 알림 하나. */
    private suspend fun notifyResults(watched: Set<Long>) {
        val dao = deps.transcripts()
        for (id in watched) {
            val t = dao.get(id) ?: continue
            if (t.stateEnum.active) continue
            val rec = deps.recordings().getById(id) ?: continue
            val ok = t.stateEnum == TranscriptState.DONE
            val text = when {
                ok -> applicationContext.getString(R.string.ai_notif_done)
                t.errorEnum == TranscriptError.GROQ_KEY -> applicationContext.getString(R.string.ai_notif_key)
                else -> applicationContext.getString(R.string.ai_notif_failed)
            }
            val n = NotificationCompat.Builder(applicationContext, ClassTimeApp.CHANNEL_TRANSCRIPTION)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(rec.subject)
                .setContentText(text)
                .setContentIntent(openIntent(id))
                .setAutoCancel(true)
                .build()
            runCatching { ClassTimeApp.notificationManager(applicationContext).notify(DONE_ID_BASE + id.toInt(), n) }
        }
    }

    private suspend fun foregroundInfo(job: Transcript): ForegroundInfo {
        val subject = deps.recordings().getById(job.recordingId)?.subject.orEmpty()
        val ctx = applicationContext
        val (text, done, total) = when (job.stateEnum) {
            TranscriptState.QUEUED, TranscriptState.PREPARING -> Triple(ctx.getString(R.string.ai_stage_preparing), 0, 0)
            TranscriptState.TRANSCRIBING -> {
                val total = TranscriptJson.plan(job.plan).size
                Triple(ctx.getString(R.string.ai_stage_transcribing), job.chunksDone, total)
            }
            else -> Triple(ctx.getString(R.string.ai_stage_correcting), job.sectionsDone, job.sectionsTotal)
        }
        val n: Notification = NotificationCompat.Builder(ctx, ClassTimeApp.CHANNEL_TRANSCRIPTION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(subject)
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent(job.recordingId))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, n)
        }
    }

    private fun openIntent(recordingId: Long): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            recordingId.toInt(),
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RECORDING_ID, recordingId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "TranscriptionWorker"
        private const val PROGRESS_ID = 4_100
        private const val DONE_ID_BASE = 4_200
        /** 알림을 누르면 이 녹음의 텍스트를 연다. */
        const val EXTRA_RECORDING_ID = "dev.iruki.classtime.extra.TRANSCRIPT_RECORDING"
    }
}
