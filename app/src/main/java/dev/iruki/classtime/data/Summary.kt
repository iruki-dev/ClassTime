package dev.iruki.classtime.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 녹음 한 건의 강의 요약(Claude). 녹음 하나에 한 행. 텍스트 변환이 끝난 녹음만 요약한다.
 *
 * 요약은 Message Batches 로 보낸다(실시간이 아니어도 되므로 요금 절반). 보낸 뒤에는 [batchId] 를
 * 남기고, 끝났는지 [checkAt] 마다 확인한다. 앱이 꺼져도 이 행에서 이어서 확인한다.
 *
 * - [content]: 요약 결과 JSON([dev.iruki.classtime.ai.SummaryJson]). 문장마다 원문 문단 번호가 붙어 있다.
 * - [transcriptAt]: 요약한 대본의 [Transcript.updatedAt]. 대본을 다시 만들면 달라진다.
 */
@Entity(tableName = "summaries")
data class Summary(
    @PrimaryKey val recordingId: Long,
    val state: String = SummaryState.QUEUED.name,
    val model: String,
    val queuedAt: Long,
    val updatedAt: Long = queuedAt,
    val transcriptAt: Long = 0,
    val batchId: String = "",
    /** 다음에 배치 상태를 확인할 시각(epoch ms). */
    val checkAt: Long = 0,
    /** 확인한 횟수. 확인 간격을 늘리는 데 쓴다. */
    val checks: Int = 0,
    val content: String = "",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    /** 실패 이유. [SummaryError] 이름 또는 ‘이름|자세한 원인’. */
    val error: String = "",
) {
    val stateEnum: SummaryState
        get() = SummaryState.entries.firstOrNull { it.name == state } ?: SummaryState.FAILED

    val errorEnum: SummaryError?
        get() = error.substringBefore('|').let { n -> SummaryError.entries.firstOrNull { it.name == n } }

    val errorDetail: String get() = error.substringAfter('|', "")
}

enum class SummaryState {
    /** 보낼 차례를 기다림. */
    QUEUED,
    /** 배치로 보냈고 결과를 기다림. */
    SUBMITTED,
    DONE,
    FAILED;

    val active get() = this == QUEUED || this == SUBMITTED
}

enum class SummaryError {
    /** Claude 키가 없거나 거부됨. */
    CLAUDE_KEY,
    /** 요약할 텍스트가 없음(변환 전이거나 지워짐). */
    NO_TEXT,
    /** 잔액·한도. */
    BILLING,
    /** 모델이 요청을 거절함(안전 분류). 다른 모델로 다시 해 볼 수 있다. */
    REFUSED,
    /** 결과가 길어 잘림. */
    TOO_LONG,
    /** 24시간 안에 처리되지 않아 만료됨. */
    EXPIRED,
    SERVER,
    UNKNOWN;

    companion object {
        fun encode(e: SummaryError, detail: String = ""): String =
            if (detail.isBlank()) e.name else e.name + "|" + detail.replace('\n', ' ').take(160)
    }
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE recordingId = :recordingId")
    fun observe(recordingId: Long): Flow<Summary?>

    @Query("SELECT * FROM summaries")
    fun observeAll(): Flow<List<Summary>>

    @Query("SELECT * FROM summaries WHERE recordingId = :recordingId")
    suspend fun get(recordingId: Long): Summary?

    @Query("SELECT * FROM summaries WHERE state IN ('QUEUED', 'SUBMITTED') ORDER BY queuedAt")
    suspend fun pending(): List<Summary>

    @Upsert
    suspend fun upsert(summary: Summary)

    @Query("DELETE FROM summaries WHERE recordingId = :recordingId")
    suspend fun delete(recordingId: Long)

    @Query("DELETE FROM summaries WHERE recordingId NOT IN (SELECT id FROM recordings)")
    suspend fun deleteOrphans(): Int
}
