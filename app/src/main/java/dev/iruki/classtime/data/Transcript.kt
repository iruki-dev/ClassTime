package dev.iruki.classtime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 녹음 한 건의 텍스트 변환 작업과 그 결과. 녹음 하나에 한 행.
 *
 * 작업은 단계마다 결과를 이 행에 저장하며 나아가므로, 앱이 죽거나 한도에 걸려 멈춰도
 * 끝낸 조각을 다시 보내지 않고 이어서 한다.
 *
 * - [plan]: 소리가 있는 구간을 나눈 조각들. JSON `[[startMs,endMs],…]`. 비어 있으면 아직 안 나눔.
 * - [segments]: Whisper 가 받아 적은 문장(걸러 낸 뒤). JSON, [TranscriptJson] 참고.
 * - [paragraphs]: 교정·문단 정리가 끝난 대본. 교정을 쓰지 않으면 원문을 문단으로 묶은 것.
 */
@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey val recordingId: Long,
    val state: String = TranscriptState.QUEUED.name,
    /** 대기열 순서. 다시 변환하면 새로 받는다. */
    val queuedAt: Long,
    val updatedAt: Long = queuedAt,
    /** 교정(LLM)까지 할지. 큐에 넣을 때의 설정을 따른다. */
    val correct: Boolean = true,
    val plan: String = "",
    val chunksDone: Int = 0,
    val segments: String = "",
    val sectionsDone: Int = 0,
    val sectionsTotal: Int = 0,
    val paragraphs: String = "",
    /** 한도 등으로 쉬는 중이면 다시 시도할 시각(epoch ms). 0 = 바로. */
    val waitUntil: Long = 0,
    /** 쉬는 이유 또는 실패 이유. [TranscriptError] 이름. */
    val error: String = "",
    /** 연속 실패 횟수. 성공하면 0. */
    val attempts: Int = 0,
    /** 실제로 보낸 소리 길이(초). 건너뛴 무음은 빠진다. */
    val speechSeconds: Int = 0,
    /** 교정한 모델. 비어 있으면 교정하지 않음. */
    val model: String = "",
) {
    val stateEnum: TranscriptState
        get() = TranscriptState.entries.firstOrNull { it.name == state } ?: TranscriptState.FAILED

    val errorEnum: TranscriptError?
        get() = TranscriptError.entries.firstOrNull { it.name == error }
}

enum class TranscriptState {
    /** 대기열에서 차례를 기다림. */
    QUEUED,
    /** 무음 구간을 찾아 조각으로 나누는 중. */
    PREPARING,
    /** 조각을 Whisper 로 보내는 중. */
    TRANSCRIBING,
    /** LLM 으로 교정·문단 정리 중. */
    CORRECTING,
    DONE,
    FAILED;

    val active get() = this != DONE && this != FAILED
}

enum class TranscriptError(val retryable: Boolean) {
    /** Groq 키가 없거나 거부됨. */
    GROQ_KEY(false),
    /** NVIDIA 키가 거부됨. 원문만 남긴다. */
    NVIDIA_KEY(false),
    /** 녹음 파일이 없어졌거나 읽을 수 없음. */
    FILE(false),
    /** 소리가 거의 없음. */
    NO_SPEECH(false),
    /** 네트워크 없음/끊김. 연결되면 이어서 한다. */
    NETWORK(true),
    /** 무료 한도. [Transcript.waitUntil] 에 이어서 한다. */
    QUOTA(true),
    /** 서버 오류. 잠시 뒤 다시. */
    SERVER(true),
    /** 교정만 실패. 원문 문단으로 끝냈다. */
    CORRECTION(false),
    UNKNOWN(false),
}
