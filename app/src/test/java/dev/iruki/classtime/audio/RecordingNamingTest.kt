package dev.iruki.classtime.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingNamingTest {

    @Test
    fun sanitize_replacesPathHostileCharacters() {
        assertThat(RecordingStorage.sanitizeSubject("운영체제/실습")).isEqualTo("운영체제_실습")
        assertThat(RecordingStorage.sanitizeSubject("C++ : 심화")).isEqualTo("C++ _ 심화")
        assertThat(RecordingStorage.sanitizeSubject("  자료구조  ")).isEqualTo("자료구조")
    }

    @Test
    fun sanitize_blankBecomesEtc() {
        assertThat(RecordingStorage.sanitizeSubject("")).isEqualTo("기타")
        assertThat(RecordingStorage.sanitizeSubject("   ")).isEqualTo("기타")
    }

    @Test
    fun sanitize_keepsKoreanAndSafePunctuation() {
        assertThat(RecordingStorage.sanitizeSubject("미분적분학(1)")).isEqualTo("미분적분학(1)")
    }

    @Test
    fun displayName_followsSubjectDateTimePattern() {
        val name = RecordingStorage.displayName("자료구조", "2026-03-04_0930")
        assertThat(name).isEqualTo("자료구조_2026-03-04_0930.m4a")
    }
}
