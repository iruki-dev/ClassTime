package dev.iruki.classtime.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.iruki.classtime.data.RecordingDao
import dev.iruki.classtime.data.SummaryDao
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.util.AppLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** 화면이 그리는 드라이브 동기화 상태. */
data class DriveConfig(
    val connected: Boolean = false,
    /** 연결한 Google 계정(알 수 있으면). */
    val email: String? = null,
    val lastSyncAt: Long = 0,
    /** 드라이브에 있는 글 수(마지막 동기화 뒤). */
    val files: Int = 0,
    /** 마지막 실패. 없으면 null. */
    val error: DriveError? = null,
    val running: Boolean = false,
)

enum class DriveError {
    /** 권한이 취소·만료돼 다시 연결해야 한다. */
    RECONNECT,
    /** Google Cloud 에 이 앱(패키지·서명)의 OAuth 클라이언트가 없다. */
    SETUP,
    /** 드라이브 저장 공간이 가득 참. */
    QUOTA,
    OTHER,
}

/** 동기화 설정과 상태. 연결 여부·마지막 결과만 남기고, 토큰은 저장하지 않는다(매번 Google 에서 받는다). */
class DriveSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<DriveConfig> = _config.asStateFlow()

    fun connected(email: String?) = edit { putBoolean(CONNECTED, true).putString(EMAIL, email).remove(ERROR) }
    fun disconnected() = edit { clear() }
    fun synced(at: Long, files: Int) = edit { putLong(LAST, at).putInt(FILES, files).remove(ERROR) }
    fun failed(error: DriveError) = edit { putString(ERROR, error.name) }
    fun running(on: Boolean) { _config.value = _config.value.copy(running = on) }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> android.content.SharedPreferences.Editor) {
        prefs.edit().block().apply()
        _config.value = read().copy(running = _config.value.running)
    }

    private fun read() = DriveConfig(
        connected = prefs.getBoolean(CONNECTED, false),
        email = prefs.getString(EMAIL, null),
        lastSyncAt = prefs.getLong(LAST, 0),
        files = prefs.getInt(FILES, 0),
        error = prefs.getString(ERROR, null)?.let { n -> DriveError.entries.firstOrNull { it.name == n } },
    )

    private companion object {
        const val CONNECTED = "connected"
        const val EMAIL = "email"
        const val LAST = "last_sync"
        const val FILES = "files"
        const val ERROR = "error"
    }
}

/** Google 권한(drive.file). 토큰은 Google Play 서비스가 들고 있다가 매번 새로 준다. */
class DriveAuth(private val context: Context) {
    private val client get() = Identity.getAuthorizationClient(context)

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** 이미 허락했으면 토큰이 든 결과, 아니면 사용자에게 물어야 하는 결과(hasResolution). */
    suspend fun authorize(): AuthorizationResult = client.authorize(request()).await()

    /** 허락 화면에서 돌아온 결과. */
    fun fromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

    suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    suspend fun revoke(email: String?) {
        if (email == null) return
        runCatching {
            client.revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(SCOPE))).setAccount(Account(email, "com.google")).build()).await()
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"

        /** 이 앱의 OAuth 클라이언트가 Google Cloud 에 없거나(패키지·SHA-1 불일치) Play 서비스 문제. */
        fun isSetupError(e: Throwable): Boolean {
            val setup = e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR
            // 사용자에게는 ‘지금은 연결할 수 없어요’만 보인다. 개발자는 logcat 에서 원인을 본다.
            if (setup) android.util.Log.w("DriveAuth", "DEVELOPER_ERROR: Drive API/OAuth client (package + SHA-1) not set up in Google Cloud", e)
            return setup
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveEntryPoint {
    fun driveSettings(): DriveSettings
    fun driveAuth(): DriveAuth
    fun recordings(): RecordingDao
    fun transcripts(): TranscriptDao
    fun summaries(): SummaryDao
}

/** 드라이브 동기화 한 번. 연결돼 있지 않으면 아무것도 하지 않는다. */
class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val deps = EntryPointAccessors.fromApplication(context.applicationContext, DriveEntryPoint::class.java)

    override suspend fun doWork(): Result = lock.withLock {
        val settings = deps.driveSettings()
        if (!settings.config.value.connected) return@withLock Result.success()
        settings.running(true)
        try {
            sync(settings)
        } finally {
            settings.running(false)
        }
    }

    private suspend fun sync(settings: DriveSettings): Result {
        val auth = deps.driveAuth()
        val result = try {
            auth.authorize()
        } catch (e: Exception) {
            AppLog.w(TAG, "드라이브 권한 확인 실패", e)
            settings.failed(if (DriveAuth.isSetupError(e)) DriveError.SETUP else DriveError.OTHER)
            return if (DriveAuth.isSetupError(e)) Result.success() else Result.retry()
        }
        val token = result.accessToken
        if (result.hasResolution() || token == null) {
            // 권한이 취소됐다. 사용자가 설정에서 다시 연결해야 한다.
            settings.failed(DriveError.RECONNECT)
            return Result.success()
        }
        val recs = deps.recordings().observeAll().first()
        val ts = deps.transcripts().observeAll().first().associateBy { it.recordingId }
        val ss = deps.summaries().observeAll().first().associateBy { it.recordingId }
        val desired = DriveDoc.from(recs, ts, ss)
        val engine = DriveSyncEngine(DriveRest(), DriveState(stateFile(applicationContext)))
        return try {
            engine.run(token, desired)
            settings.synced(System.currentTimeMillis(), desired.size)
            Result.success()
        } catch (e: DriveException) {
            AppLog.w(TAG, "드라이브 동기화 실패 ${e.kind}", e)
            when (e.kind) {
                DriveException.Kind.AUTH -> {
                    // 만료된 토큰을 버리고 한 번 더(다음 번엔 새 토큰).
                    auth.clearToken(token)
                    if (runAttemptCount < 2) Result.retry() else { settings.failed(DriveError.RECONNECT); Result.success() }
                }
                DriveException.Kind.QUOTA -> { settings.failed(DriveError.QUOTA); Result.success() }
                DriveException.Kind.RETRY -> Result.retry()
                DriveException.Kind.OTHER -> { settings.failed(DriveError.OTHER); Result.success() }
            }
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DriveSync"
        private const val WORK = "drive_sync"
        private val lock = Mutex()

        fun stateFile(context: Context) = File(context.filesDir, "drive_sync.json")

        /**
         * 동기화를 맡긴다(연결돼 있을 때만 실제로 한다). 텍스트·요약이 바뀔 때마다 부르므로,
         * 몰려 오면 잠시 모아서 한 번에 한다.
         */
        fun kick(context: Context, delayMs: Long = 15_000) {
            val prefs = context.applicationContext.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("connected", false)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DriveSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build(),
            )
        }
    }
}
