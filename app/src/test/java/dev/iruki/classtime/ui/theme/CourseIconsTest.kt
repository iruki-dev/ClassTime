package dev.iruki.classtime.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourseIconsTest {

    private fun guess(subject: String) = CourseIcons.guess(subject).key

    @Test
    fun keys_areUnique() {
        val keys = CourseIcons.all.map { it.key }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun guess_prefersTheMoreSpecificRule() {
        // ‘컴퓨터’가 들어 있어도 프로그래밍이면 코드, 구조면 칩.
        assertThat(guess("컴퓨터프로그래밍")).isEqualTo("code")
        assertThat(guess("컴퓨터구조")).isEqualTo("memory")
        // ‘데이터’가 들어 있어도 데이터베이스는 통계가 아니다.
        assertThat(guess("데이터베이스")).isEqualTo("computer")
        assertThat(guess("데이터 분석")).isEqualTo("stats")
        // ‘문법’의 ‘법’ 때문에 법학이 되면 안 된다.
        assertThat(guess("영어 문법")).isEqualTo("translate")
        assertThat(guess("민법총칙")).isEqualTo("gavel")
    }

    @Test
    fun guess_commonSubjects() {
        assertThat(guess("미적분학 1")).isEqualTo("functions")
        assertThat(guess("일반화학실험")).isEqualTo("science")
        assertThat(guess("생명과학")).isEqualTo("biotech")
        assertThat(guess("경영학원론")).isEqualTo("business")
        assertThat(guess("Data Structures")).isEqualTo("stats")
        assertThat(guess("Algorithms")).isEqualTo("code")
    }

    @Test
    fun unknownOrBlank_fallsBackToBook() {
        assertThat(guess("")).isEqualTo("book")
        assertThat(guess("특강")).isEqualTo("book")
    }

    @Test
    fun of_usesStoredKey_andFallsBackToGuessForUnknownKeys() {
        assertThat(CourseIcons.of("music", "자료구조").key).isEqualTo("music")
        assertThat(CourseIcons.of("", "자료구조").key).isEqualTo("code")
        assertThat(CourseIcons.of("deleted-icon", "자료구조").key).isEqualTo("code")
    }
}
