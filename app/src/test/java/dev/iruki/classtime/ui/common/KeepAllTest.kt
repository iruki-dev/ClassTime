package dev.iruki.classtime.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeepAllTest {
    private val wj = '⁠'

    @Test
    fun joinsHangulWithinWords_butNotAcrossSpaces() {
        assertThat("힙 정렬까지".keepAll()).isEqualTo("힙 정${wj}렬${wj}까${wj}지")
    }

    @Test
    fun keepsParticlesOnLatinWords_andAllowsBreaksAtDashes() {
        assertThat("heapify가".keepAll()).isEqualTo("heapify${wj}가")
        assertThat("O(n)인".keepAll()).isEqualTo("O(n)${wj}인")
        assertThat("힙—정렬".keepAll()).isEqualTo("힙—정${wj}렬")
    }

    @Test
    fun leavesTextWithoutHangulAlone() {
        assertThat("sift-down O(n log n)".keepAll()).isEqualTo("sift-down O(n log n)")
        assertThat("".keepAll()).isEmpty()
    }
}
