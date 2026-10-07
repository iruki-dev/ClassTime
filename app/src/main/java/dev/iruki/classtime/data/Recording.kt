package dev.iruki.classtime.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 완료되었거나 진행 중인 녹음 한 건.
 *
 * @param uri MediaStore(또는 파일) content uri 문자열. 재생·공유·삭제에 사용.
 * @param relativePath 사용자에게 보여줄 저장 위치 (예: Music/ClassTime/자료구조)
 * @param startedAt epoch millis
 * @param durationMs 종료 시 확정. 진행 중이면 0.
 * @param auto 자동 녹음이면 true, 수동이면 false
 * @param ongoing 아직 녹음 중이면 true
 * @param peakAmplitude 녹음 내내 관측한 최대 진폭. -1 = 측정 안 됨(구버전), 0 = **무음**, >0 = 정상.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long? = null,
    val subject: String,
    val professor: String = "",
    val fileName: String,
    val uri: String,
    val relativePath: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val auto: Boolean = false,
    val ongoing: Boolean = false,
    val peakAmplitude: Int = UNKNOWN_AMPLITUDE,
) {
    /** 소리가 전혀 담기지 않은 녹음. 마이크가 차단된 채로 녹음됐다는 뜻. */
    val isSilent get() = !ongoing && peakAmplitude == 0

    companion object {
        const val UNKNOWN_AMPLITUDE = -1
    }
}
