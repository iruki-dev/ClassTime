package dev.iruki.classtime.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.iruki.classtime.audio.RecordingScanner
import dev.iruki.classtime.audio.RecordingStorage
import dev.iruki.classtime.data.AppDatabase
import dev.iruki.classtime.data.CourseDao
import dev.iruki.classtime.data.RecordingDao
import dev.iruki.classtime.data.ScheduleExceptionDao
import dev.iruki.classtime.data.TermDao
import dev.iruki.classtime.data.TranscriptDao
import dev.iruki.classtime.ai.AiSettings
import dev.iruki.classtime.ai.SecretStore
import dev.iruki.classtime.ai.TranscriptionEngine
import dev.iruki.classtime.ai.TranscriptionQueue
import dev.iruki.classtime.util.AppSettings
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 프로세스가 살아 있는 동안 유지되는 코루틴 스코프.
 *
 * 녹음 마무리(파일 확정 + DB 갱신)처럼 **시작한 컴포넌트가 죽어도 반드시 끝나야 하는**
 * 작업에만 쓴다. 서비스 스코프에서 돌리면 서비스가 내려가는 순간 취소되어 파일이
 * 재생 불가 상태로 남는다.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

    @Provides fun provideCourseDao(db: AppDatabase): CourseDao = db.courseDao()
    @Provides fun provideRecordingDao(db: AppDatabase): RecordingDao = db.recordingDao()
    @Provides fun provideTermDao(db: AppDatabase): TermDao = db.termDao()
    @Provides fun provideExceptionDao(db: AppDatabase): ScheduleExceptionDao =
        db.scheduleExceptionDao()
    @Provides fun provideTranscriptDao(db: AppDatabase): TranscriptDao = db.transcriptDao()

    // --- 실험적 기능: AI 텍스트 변환 ---

    @Provides
    @Singleton
    fun provideSecretStore(@ApplicationContext context: Context): SecretStore = SecretStore(context)

    @Provides
    @Singleton
    fun provideAiSettings(@ApplicationContext context: Context, secrets: SecretStore): AiSettings =
        AiSettings(context, secrets)

    @Provides
    @Singleton
    fun provideTranscriptionEngine(
        @ApplicationContext context: Context,
        transcripts: TranscriptDao,
        recordings: RecordingDao,
        settings: AiSettings,
    ): TranscriptionEngine = TranscriptionEngine(context, transcripts, recordings, settings)

    @Provides
    @Singleton
    fun provideTranscriptionQueue(
        @ApplicationContext context: Context,
        transcripts: TranscriptDao,
        settings: AiSettings,
    ): TranscriptionQueue = TranscriptionQueue(context, transcripts, settings)

    @Provides
    @Singleton
    fun provideRecordingStorage(@ApplicationContext context: Context): RecordingStorage =
        RecordingStorage(context)

    @Provides
    @Singleton
    fun provideRecordingScanner(@ApplicationContext context: Context): RecordingScanner =
        RecordingScanner(context)

    @Provides
    @Singleton
    fun provideAppSettings(@ApplicationContext context: Context): AppSettings =
        AppSettings(context)

    /** SupervisorJob 이라 한 건의 실패가 다른 마무리 작업을 함께 죽이지 않는다. */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
