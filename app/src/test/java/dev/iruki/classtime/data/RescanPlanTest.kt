package dev.iruki.classtime.data

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.audio.RecordingScanner
import org.junit.Test

class RescanPlanTest {

    private fun found(uri: String, name: String, subject: String = "자료구조", size: Long = 43_000_000, ms: Long = 3_600_000) =
        RecordingScanner.Found(uri, name, "Music/ClassTime/$subject", subject, 1_000L, ms, size)

    private fun row(uri: String, name: String, ongoing: Boolean = false) = Recording(
        subject = "자료구조", fileName = name, uri = uri,
        relativePath = "Music/ClassTime/자료구조", startedAt = 1_000L, ongoing = ongoing,
    )

    @Test
    fun newFiles_areAdded_withCourseLink() {
        val plan = RescanPlan.of(
            existing = emptyList(),
            found = listOf(found("content://m/1", "자료구조_2026-03-02_0900.m4a")),
            courseIdOf = { if (it == "자료구조") 7L else null },
        )
        assertThat(plan.toAdd).hasSize(1)
        assertThat(plan.toAdd.single().courseId).isEqualTo(7L)
        assertThat(plan.toAdd.single().auto).isFalse()
        assertThat(plan.missingCandidates).isEmpty()
    }

    @Test
    fun knownFiles_areNotDuplicated_evenWhenTheUriChanged() {
        // 재설치하면 같은 파일이라도 uri 가 바뀔 수 있다. 위치 + 이름이 같으면 같은 파일.
        val plan = RescanPlan.of(
            existing = listOf(row("content://m/old", "a.m4a")),
            found = listOf(found("content://m/new", "a.m4a")),
            courseIdOf = { null },
        )
        assertThat(plan.toAdd).isEmpty()
        assertThat(plan.missingCandidates).isEmpty()
    }

    @Test
    fun rowsWithoutAFile_becomeCandidates_butNeverTheOngoingOne() {
        val plan = RescanPlan.of(
            existing = listOf(row("content://m/1", "gone.m4a"), row("content://m/2", "now.m4a", ongoing = true)),
            found = emptyList(),
            courseIdOf = { null },
        )
        assertThat(plan.missingCandidates.map { it.fileName }).containsExactly("gone.m4a")
    }

    @Test
    fun lowBitrateFiles_areMarkedCompressed() {
        // 1시간에 14MB 남짓 = 32kbps.
        assertThat(RescanPlan.looksCompressed(14_400_000, 3_600_000)).isTrue()
        // 1시간에 43MB = 96kbps 원본.
        assertThat(RescanPlan.looksCompressed(43_200_000, 3_600_000)).isFalse()
        assertThat(RescanPlan.looksCompressed(100, 0)).isFalse()
    }
}
