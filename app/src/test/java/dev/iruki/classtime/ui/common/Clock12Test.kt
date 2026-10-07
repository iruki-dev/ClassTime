package dev.iruki.classtime.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Clock12Test {

    @Test
    fun morningAfternoonAndTheTwoTwelves() {
        assertThat(Clock12.of(9 * 60)).isEqualTo(Clock12(pm = false, hour = 9, minute = 0))
        assertThat(Clock12.of(13 * 60 + 30)).isEqualTo(Clock12(pm = true, hour = 1, minute = 30))
        // 오전 12시 = 자정, 오후 12시 = 정오.
        assertThat(Clock12.of(0)).isEqualTo(Clock12(pm = false, hour = 12, minute = 0))
        assertThat(Clock12.of(12 * 60 + 5)).isEqualTo(Clock12(pm = true, hour = 12, minute = 5))
    }

    @Test
    fun roundTripsEveryMinuteOfTheDay() {
        for (m in 0 until 24 * 60) {
            assertThat(Clock12.of(m).toMinuteOfDay()).isEqualTo(m)
        }
    }

    @Test
    fun timePicker_layoutFollowsWindowHeight_soTheDialNeverGetsSquashed() {
        assertThat(TimePickerFit.of(800)).isEqualTo(TimePickerFit.VERTICAL)
        // 가로 화면 휴대폰(약 390dp): 다이얼을 옆에 두는 가로 배치.
        assertThat(TimePickerFit.of(390)).isEqualTo(TimePickerFit.HORIZONTAL)
        // 분할 화면처럼 그보다 낮으면 다이얼이 들어가지 않으니 입력으로.
        assertThat(TimePickerFit.of(300)).isEqualTo(TimePickerFit.INPUT)
    }
}
