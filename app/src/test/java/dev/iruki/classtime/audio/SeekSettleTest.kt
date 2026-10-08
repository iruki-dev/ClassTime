package dev.iruki.classtime.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeekSettleTest {

    @Test
    fun positionSlightlyBeforeTheTarget_doesNotFlickerBack() {
        val s = SeekSettle()
        s.begin(61_000, now = 0)
        // 이동이 끝나기 전: 예전 위치(뒤로 이동했을 때) → 목표를 보고.
        assertThat(s.report(300_000, now = 100)).isEqualTo(61_000)
        s.completed()
        // 끝난 직후: 앞쪽 프레임 경계(목표보다 조금 앞) → 여전히 목표.
        assertThat(s.report(60_977, now = 250)).isEqualTo(61_000)
        // 목표를 지나면 실제 위치를 그대로.
        assertThat(s.report(61_200, now = 500)).isEqualTo(61_200)
        assertThat(s.report(61_450, now = 750)).isEqualTo(61_450)
    }

    @Test
    fun givesUpAfterTimeout_whenTheDeviceNeverReportsCompletion() {
        val s = SeekSettle(timeoutMs = 1_000)
        s.begin(10_000, now = 0)
        assertThat(s.report(9_990, now = 500)).isEqualTo(10_000)
        assertThat(s.report(9_990, now = 1_001)).isEqualTo(9_990)
    }

    @Test
    fun withoutSeek_reportsAsIs() {
        assertThat(SeekSettle().report(1_234, now = 0)).isEqualTo(1_234)
    }
}
