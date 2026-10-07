package dev.iruki.classtime.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 녹음 기록에서 시간표를 거꾸로 짐작한다. 시간표를 잃어버렸을 때 임시로 다시 세우는 용도.
 *
 * 녹음 기록에는 시간표에 없는 일이 섞여 있다. 각각을 이렇게 걸러 낸다.
 *
 * | 섞여 있는 일 | 기록에 남는 모습 | 걸러 내는 방법 |
 * | --- | --- | --- |
 * | 보강 | 다른 요일·시각에 한두 번 | 그 자리에서 **여러 주** 반복된 것만 수업으로 인정 |
 * | 휴강 | 그 주에 기록이 없음 | 반복 기준을 ‘매주’가 아니라 ‘활동한 주의 1/3 이상’으로 |
 * | 수업 연장 | 끝 시각이 가끔 늦음 | 끝 시각은 **가장 흔한 값(최빈값)** |
 * | 일찍 끝남 | 끝 시각이 가끔 이름 | 〃 |
 * | 늦게 누른 수동 녹음 | 시작이 몇 분씩 늦음 | 자동 녹음 시각을 우선, 없으면 **이른 쪽(하위 25%)** |
 * | 실수로 누른 짧은 녹음 | 몇 분짜리 | [MIN_DURATION_MIN] 미만은 버림 |
 * | 다른 과목과 겹침 | 같은 시각에 두 과목 | 더 자주 반복된 쪽만 남김 |
 *
 * 시각은 5분 단위로 맞춘다. 대학 시간표는 거의 예외 없이 5분 단위다.
 */
object TimetableInference {

    /** 녹음 한 건에서 필요한 것만. */
    data class Sample(
        val subject: String,
        val start: LocalDateTime,
        val durationMinutes: Int,
        /** 자동 녹음이면 시작 시각이 곧 수업 시작 시각이다. */
        val auto: Boolean,
    )

    /** 짐작한 수업 칸 하나. */
    data class Slot(
        val subject: String,
        val dayOfWeek: Int,
        val startMinute: Int,
        val endMinute: Int,
        /** 이 칸에서 녹음이 있었던 주의 수. */
        val weeksSeen: Int,
        /** 이 과목을 녹음한 주의 수. weeksSeen 의 분모. */
        val weeksActive: Int,
    ) {
        val confidence: Float get() = weeksSeen.toFloat() / weeksActive.coerceAtLeast(1)
    }

    /** 과목 하나와 그 칸들. */
    data class Result(val subject: String, val slots: List<Slot>)

    const val MIN_DURATION_MIN = 10

    /** 같은 요일 안에서 이보다 멀리 떨어진 시작 시각은 다른 수업으로 본다. */
    private const val SAME_SLOT_GAP_MIN = 60

    /** 활동한 주 가운데 이 비율 이상 반복돼야 정규 수업. 휴강 몇 번은 견딘다. */
    private const val MIN_SHARE = 1.0 / 3

    fun infer(samples: List<Sample>, ignoreSubjects: Set<String> = emptySet()): List<Result> {
        val usable = samples.filter {
            it.subject.isNotBlank() && it.subject !in ignoreSubjects && it.durationMinutes >= MIN_DURATION_MIN
        }
        val slots = usable.groupBy { it.subject }.flatMap { (subject, list) -> slotsFor(subject, list) }
        return resolveConflicts(slots)
            .groupBy { it.subject }
            .map { (subject, list) -> Result(subject, list.sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute }))) }
            .sortedWith(compareBy({ it.slots.first().dayOfWeek }, { it.slots.first().startMinute }))
    }

    private fun slotsFor(subject: String, list: List<Sample>): List<Slot> {
        val activeWeeks = list.map { weekOf(it.start) }.toSet().size
        // 한 주 치 기록뿐이면 반복을 확인할 방법이 없다. 그 주에 본 것을 그대로 믿는다.
        val needed = if (activeWeeks <= 1) 1 else maxOf(2, ceil(activeWeeks * MIN_SHARE).toInt())

        return list.groupBy { it.start.dayOfWeek.value }.flatMap { (day, ofDay) ->
            clusterByStart(ofDay).mapNotNull { cluster ->
                val weeks = cluster.map { weekOf(it.start) }.toSet().size
                if (weeks < needed) return@mapNotNull null
                val start = estimateStart(cluster)
                val end = estimateEnd(cluster).coerceAtLeast(start + MIN_DURATION_MIN)
                Slot(subject, day, start, end.coerceAtMost(24 * 60 - 1), weeks, activeWeeks)
            }
        }
    }

    /** 시작 시각이 가까운 것끼리 묶는다. 같은 요일 오전·오후 두 번 있는 과목을 위해. */
    private fun clusterByStart(list: List<Sample>): List<List<Sample>> {
        val sorted = list.sortedBy { minuteOf(it.start) }
        val clusters = mutableListOf<MutableList<Sample>>()
        for (s in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && minuteOf(s.start) - minuteOf(last.first().start) <= SAME_SLOT_GAP_MIN) {
                last += s
            } else {
                clusters += mutableListOf(s)
            }
        }
        return clusters
    }

    /**
     * 시작 시각. 자동 녹음은 수업 시작에 정확히 켜지므로 그 값을 믿는다(여럿이면 최빈값).
     * 수동 녹음만 있으면 사람은 늦게 누르지 일찍 누르지 않으므로 이른 쪽을 고른다.
     */
    private fun estimateStart(cluster: List<Sample>): Int {
        val autos = cluster.filter { it.auto }.map { minuteOf(it.start) }
        val raw = if (autos.isNotEmpty()) mode(autos) ?: median(autos)
        else percentile(cluster.map { minuteOf(it.start) }, 0.25)
        return roundTo5(raw)
    }

    /** 끝 시각. 연장·조기 종료는 가끔이고 제때 끝난 날이 가장 많다 → 최빈값, 없으면 중앙값. */
    private fun estimateEnd(cluster: List<Sample>): Int {
        val ends = cluster.map { roundTo5(minuteOf(it.start) + it.durationMinutes) }
        return mode(ends) ?: roundTo5(median(ends))
    }

    /**
     * 서로 다른 과목이 같은 요일·시각에 겹치면 둘 중 하나는 이름을 잘못 붙인 녹음이다.
     * 더 많은 주에 반복된 쪽을 남긴다.
     */
    private fun resolveConflicts(slots: List<Slot>): List<Slot> {
        val kept = mutableListOf<Slot>()
        for (s in slots.sortedWith(compareByDescending<Slot> { it.weeksSeen }.thenByDescending { it.confidence })) {
            val clash = kept.any { k ->
                k.subject != s.subject && k.dayOfWeek == s.dayOfWeek &&
                    overlap(k, s) * 2 > (s.endMinute - s.startMinute)
            }
            if (!clash) kept += s
        }
        return kept
    }

    private fun overlap(a: Slot, b: Slot): Int =
        (minOf(a.endMinute, b.endMinute) - maxOf(a.startMinute, b.startMinute)).coerceAtLeast(0)

    private fun weekOf(t: LocalDateTime): Long =
        t.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()

    private fun minuteOf(t: LocalDateTime): Int = t.hour * 60 + t.minute

    private fun roundTo5(minute: Int): Int = ((minute / 5.0).roundToInt() * 5)

    /** 가장 많이 나온 값. 한 번씩만 나왔으면(뚜렷한 최빈값이 없으면) null. 동률이면 이른 값. */
    private fun mode(values: List<Int>): Int? {
        val counts = values.groupingBy { it }.eachCount()
        val best = counts.values.maxOrNull() ?: return null
        if (best < 2 && values.size > 1) return null
        return counts.filterValues { it == best }.keys.min()
    }

    private fun median(values: List<Int>): Int = percentile(values, 0.5)

    private fun percentile(values: List<Int>, p: Double): Int {
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p)
        val lo = sorted[idx.toInt()]
        val hi = sorted[minOf(idx.toInt() + 1, sorted.lastIndex)]
        return (lo + (hi - lo) * (idx - idx.toInt())).roundToInt()
    }
}
