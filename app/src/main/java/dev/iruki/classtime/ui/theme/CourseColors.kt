package dev.iruki.classtime.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.iruki.classtime.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 과목 색 하나. DB 에는 [seed] 만 저장하고, 화면은 시간표 칸 톤을 쓴다.
 *
 * 색은 **시간표 칸(과 색 고르기·플레이어 아트)에만** 쓴다. 목록·시트의 과목 표시는 무채색
 * 아이콘 타일이 맡는다 — 줄마다 색이 바뀌면 화면이 알록달록해지고 상태 색이 묻힌다.
 *
 * 톤은 HCT 기준. 라이트: 칸 T80(채도 최대 44) + 글자 T20(7.7:1) + 보조 T28(5.9:1).
 * 다크: 칸 T34 + 글자 T92(6.6:1). 예전 T90 컨테이너는 흰 카드 위에서 회색처럼 보여
 * 시간표가 밋밋했다.
 */
data class CourseColor(
    val seed: Int,
    @StringRes val label: Int,
    val block: Int,
    val onBlock: Int,
    val blockSub: Int,
    val blockDark: Int,
    val onBlockDark: Int,
)

object CourseColors {

    val palette: List<CourseColor> = listOf(
        color(0xFFAB5034, R.string.color_coral, 0xFFFFB59F, 0xFF581D0A, 0xFF6C301E, 0xFF853921, 0xFFFFE2DA),
        color(0xFF926000, R.string.color_amber, 0xFFFCBA58, 0xFF452B00, 0xFF5C3B00, 0xFF6F4800, 0xFFFFE4C3),
        color(0xFF697000, R.string.color_lime, 0xFFC6CD66, 0xFF303300, 0xFF414600, 0xFF4F5400, 0xFFE9ECB4),
        color(0xFF2A793C, R.string.color_green, 0xFF8FD795, 0xFF003913, 0xFF184C24, 0xFF145C28, 0xFFCFF1CD),
        color(0xFF007871, R.string.color_teal, 0xFF64D9CF, 0xFF003734, 0xFF004B46, 0xFF005A55, 0xFFC1F2EC),
        color(0xFF007398, R.string.color_sky, 0xFF77D1FE, 0xFF003548, 0xFF004860, 0xFF005774, 0xFFCEEDFF),
        color(0xFF4B67B6, R.string.color_indigo, 0xFFB4C5FF, 0xFF142D68, 0xFF2D4073, 0xFF334D91, 0xFFE3E7FF),
        color(0xFF7B5AAE, R.string.color_violet, 0xFFD6BAFF, 0xFF3C2361, 0xFF4D376E, 0xFF5E418A, 0xFFF1E3FF),
        color(0xFFA04E84, R.string.color_pink, 0xFFFFAEDE, 0xFF531A42, 0xFF652F53, 0xFF7D3766, 0xFFFFE0EF),
        color(0xFF6A6A70, R.string.color_grey, 0xFFC6C6CD, 0xFF2F3036, 0xFF414248, 0xFF4F5056, 0xFFE9E7EC),
    )

    private fun color(seed: Long, @StringRes label: Int, block: Long, on: Long, sub: Long, dark: Long, onDark: Long) =
        CourseColor(seed.toInt(), label, block.toInt(), on.toInt(), sub.toInt(), dark.toInt(), onDark.toInt())

    val default: CourseColor get() = palette[5]

    /**
     * 예전에 저장된 색을 원래 의도와 가장 가까운 새 색으로 옮긴다.
     * v1 의 빨강은 녹음 색과 겹치므로 분홍으로 보낸다.
     */
    private val legacy: Map<Int, Int> = mapOf(
        // v1
        0xFF2E6C3E.toInt() to 3, // 초록
        0xFF1B5E20.toInt() to 3, // 예전 Course 기본값(초록)
        0xFF1565C0.toInt() to 6, // 파랑 → 남색
        0xFF6A1B9A.toInt() to 7, // 보라 (예전 보강 색)
        0xFFC62828.toInt() to 8, // 빨강 → 분홍
        0xFFEF6C00.toInt() to 1, // 주황 → 호박
        0xFF00838F.toInt() to 4, // 청록
        0xFF4E342E.toInt() to 1, // 갈색 → 호박
        0xFF37474F.toInt() to 9, // 청회색 → 회색
        // v3 (8색)
        0xFF636100.toInt() to 2, // 올리브 → 라임
        0xFF1A6C31.toInt() to 3,
        0xFF006A64.toInt() to 4,
        0xFF006685.toInt() to 5,
        0xFF2F5DA8.toInt() to 6, // 파랑 → 남색
        0xFF684FA4.toInt() to 7,
        0xFF8E437E.toInt() to 8,
        0xFF5E5E67.toInt() to 9,
    )

    /** 저장된 ARGB 를 팔레트 색으로. 모르는 색은 색조가 가장 가까운 것으로. */
    fun of(argb: Int): CourseColor {
        palette.firstOrNull { it.seed == argb }?.let { return it }
        legacy[argb]?.let { return palette[it] }
        val (h, s) = hueSat(argb)
        if (s < 0.15f) return palette.last()
        return palette.dropLast(1).minBy { hueDistance(hueSat(it.seed).first, h) }
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return min(d, 360f - d)
    }

    /** HSV 색조(0..360)와 채도. android.graphics.Color 없이 계산해 JVM 테스트가 가능하다. */
    internal fun hueSat(argb: Int): Pair<Float, Float> {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return h to s
    }
}

/** 지금 테마에 맞는 (칸, 글자) 색. */
@Composable
@ReadOnlyComposable
fun CourseColor.tones(): Pair<Color, Color> =
    if (LocalAppColors.current.isDark) Color(blockDark) to Color(onBlockDark)
    else Color(block) to Color(onBlock)

/** 칸 안의 보조 글자(강의실 등). */
@Composable
@ReadOnlyComposable
fun CourseColor.subTone(): Color =
    if (LocalAppColors.current.isDark) Color(onBlockDark) else Color(blockSub)
