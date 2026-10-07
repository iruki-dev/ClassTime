package dev.iruki.classtime.ai

/**
 * Groq 무료 한도 안에서 보낼 시각을 정한다. 순수 계산 — 기록 보관은 [UsageStore].
 *
 * whisper-large-v3 무료 등급: 분당 20회, 하루 2,000회, 시간당 소리 7,200초, 하루 28,800초.
 * 서버도 막아 주지만 막힌 뒤 기다리는 것보다 미리 나눠 보내는 편이 빠르고, 같은 키를 다른
 * 곳에서 쓸 수도 있어 한도의 [margin] 만큼만 쓴다. 서버가 429 를 주면 그 값이 우선이다.
 */
class QuotaLedger(
    private val perMinuteRequests: Int = 20,
    private val perDayRequests: Int = 2_000,
    private val perHourSeconds: Int = 7_200,
    private val perDaySeconds: Int = 28_800,
    private val margin: Double = 0.9,
) {

    /** 보낸 기록 하나. [seconds] 는 청구 기준(최소 10초). */
    data class Use(val at: Long, val seconds: Int)

    /**
     * [seconds] 짜리 조각을 지금 보내도 되면 0, 아니면 기다릴 ms.
     * 하루 한도보다 큰 조각은 없으므로(조각 ≤ 13분) 언젠가는 반드시 보낼 수 있다.
     */
    fun waitMs(history: List<Use>, now: Long, seconds: Int): Long {
        val billed = maxOf(seconds, MIN_BILLED_SECONDS)
        var wait = 0L
        wait = maxOf(wait, windowWait(history, now, MINUTE) { it.size + 1 > perMinuteRequests })
        wait = maxOf(wait, windowWait(history, now, DAY) { it.size + 1 > (perDayRequests * margin).toInt() })
        wait = maxOf(wait, windowWait(history, now, HOUR) { it.sumOf { u -> u.seconds } + billed > perHourSeconds * margin })
        wait = maxOf(wait, windowWait(history, now, DAY) { it.sumOf { u -> u.seconds } + billed > perDaySeconds * margin })
        return wait
    }

    /** 지난 한 시간·하루 동안 보낸 소리(초). 설정 화면의 사용량 표시용. */
    fun usedSeconds(history: List<Use>, now: Long): Pair<Int, Int> =
        history.filter { it.at > now - HOUR }.sumOf { it.seconds } to
            history.filter { it.at > now - DAY }.sumOf { it.seconds }

    val dailySeconds get() = (perDaySeconds * margin).toInt()
    val hourlySeconds get() = (perHourSeconds * margin).toInt()

    /**
     * [window] 안의 기록이 [over] 이면, 가장 오래된 기록부터 하나씩 창 밖으로 밀려날 때까지
     * 기다린다. 기록이 빠질 때마다 다시 확인하므로 필요한 만큼만 기다린다.
     */
    private fun windowWait(history: List<Use>, now: Long, window: Long, over: (List<Use>) -> Boolean): Long {
        val inWindow = history.filter { it.at > now - window }.sortedBy { it.at }
        var drop = 0
        while (drop <= inWindow.size && over(inWindow.drop(drop))) drop++
        if (drop == 0) return 0
        val freedBy = inWindow[drop - 1].at + window
        return (freedBy - now + 1_000).coerceAtLeast(0)
    }

    /** 하루가 지난 기록은 버린다. */
    fun prune(history: List<Use>, now: Long): List<Use> = history.filter { it.at > now - DAY }

    companion object {
        const val MIN_BILLED_SECONDS = 10
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
