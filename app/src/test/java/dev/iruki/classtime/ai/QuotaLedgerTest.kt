package dev.iruki.classtime.ai

import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.ai.QuotaLedger.Companion.HOUR
import dev.iruki.classtime.ai.QuotaLedger.Companion.MINUTE
import org.junit.Test

class QuotaLedgerTest {

    private val ledger = QuotaLedger()
    private val t0 = 1_000_000_000L

    @Test
    fun freshKey_sendsRightAway() {
        assertThat(ledger.waitMs(emptyList(), t0, 600)).isEqualTo(0)
    }

    @Test
    fun hourlyAudio_isSpreadOut() {
        // 한 시간에 10분 조각 10개(6,000초) = 7,200×0.9 = 6,480 에 닿기 직전.
        val history = (0 until 10).map { QuotaLedger.Use(t0 + it * 2 * MINUTE, 600) }
        val now = t0 + 20 * MINUTE
        val wait = ledger.waitMs(history, now, 600)
        // 첫 조각이 한 시간 창에서 빠질 때까지 기다린다.
        assertThat(wait).isAtLeast(t0 + HOUR - now)
        assertThat(wait).isAtMost(t0 + HOUR - now + 2_000)
    }

    @Test
    fun requestsPerMinute_areRespected() {
        val history = (0 until 20).map { QuotaLedger.Use(t0 + it * 1_000L, 10) }
        val wait = ledger.waitMs(history, t0 + 20_000, 10)
        assertThat(wait).isGreaterThan(0)
        assertThat(wait).isAtMost(MINUTE)
    }

    @Test
    fun shortChunk_isBilledAsTenSeconds() {
        val history = listOf(QuotaLedger.Use(t0, 6_475))
        assertThat(ledger.waitMs(history, t0 + 1, 3)).isGreaterThan(0)
    }

    @Test
    fun dailyLimit_waitsForTheOldestToExpire() {
        // 하루 25,920초(=28,800×0.9) 를 시간당 한도 안에서 고르게 채운다.
        val history = (0 until 24).map { QuotaLedger.Use(t0 + it * HOUR, 1_080) }
        val now = t0 + 23 * HOUR + 30 * MINUTE
        val wait = ledger.waitMs(history, now, 600)
        assertThat(wait).isGreaterThan(0)
        assertThat(ledger.usedSeconds(history, now).second).isEqualTo(25_920)
    }
}
