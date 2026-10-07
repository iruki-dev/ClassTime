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
 * 과목 색 하나. DB 에는 [seed] 만 저장하고, 화면은 톤 쌍을 쓴다.
 *
 * 톤은 HCT 기준: 라이트는 컨테이너 T90 + 글자 T30, 다크는 컨테이너 T30 + 글자 T90.
 * 모든 쌍이 7:1 이상이다. 색조는 110°–340° 에서 고르게 나눴고, 녹음 빨강(27°)과
 * 확인 필요 노랑(75°)에서 35° 이상 떨어뜨렸다.
 */
data class CourseColor(
    val seed: Int,
    @StringRes val label: Int,
    val container: Int,
    val onContainer: Int,
    val containerDark: Int,
    val onContainerDark: Int,
    val accentDark: Int,
)

object CourseColors {

    val palette: List<CourseColor> = listOf(
        CourseColor(0xFF636100.toInt(), R.string.color_olive, 0xFFEAE784.toInt(), 0xFF4A4900.toInt(), 0xFF4A4900.toInt(), 0xFFEAE784.toInt(), 0xFFCECB56.toInt()),
        CourseColor(0xFF1A6C31.toInt(), R.string.color_green, 0xFFB1F2B4.toInt(), 0xFF135224.toInt(), 0xFF135224.toInt(), 0xFFB1F2B4.toInt(), 0xFF88D990.toInt()),
        CourseColor(0xFF006A64.toInt(), R.string.color_teal, 0xFF90F3EA.toInt(), 0xFF00504B.toInt(), 0xFF00504B.toInt(), 0xFF90F3EA.toInt(), 0xFF50DBD0.toInt()),
        CourseColor(0xFF006685.toInt(), R.string.color_sky, 0xFFBFE9FF.toInt(), 0xFF004D65.toInt(), 0xFF004D65.toInt(), 0xFFBFE9FF.toInt(), 0xFF6DD2FF.toInt()),
        CourseColor(0xFF2F5DA8.toInt(), R.string.color_blue, 0xFFD7E2FF.toInt(), 0xFF23467F.toInt(), 0xFF23467F.toInt(), 0xFFD7E2FF.toInt(), 0xFFACC7FF.toInt()),
        CourseColor(0xFF684FA4.toInt(), R.string.color_violet, 0xFFE9DDFF.toInt(), 0xFF4E3B7C.toInt(), 0xFF4E3B7C.toInt(), 0xFFE9DDFF.toInt(), 0xFFD0BCFF.toInt()),
        CourseColor(0xFF8E437E.toInt(), R.string.color_pink, 0xFFFFD7F0.toInt(), 0xFF6C325F.toInt(), 0xFF6C325F.toInt(), 0xFFFFD7F0.toInt(), 0xFFFFACE7.toInt()),
        CourseColor(0xFF5E5E67.toInt(), R.string.color_grey, 0xFFE3E1EC.toInt(), 0xFF46464F.toInt(), 0xFF46464F.toInt(), 0xFFE3E1EC.toInt(), 0xFFC7C5D0.toInt()),
    )

    val default: CourseColor get() = palette[3]

    /**
     * 예전 팔레트와 기본값으로 저장된 색. 원래 의도와 가장 가까운 새 색으로 옮긴다.
     * 빨강·주황·갈색은 녹음/확인 필요 색과 겹치므로 가까운 다른 색으로 보낸다.
     */
    private val legacy: Map<Int, Int> = mapOf(
        0xFF2E6C3E.toInt() to 1, // 초록
        0xFF1B5E20.toInt() to 1, // 예전 Course 기본값(초록)
        0xFF1565C0.toInt() to 4, // 파랑
        0xFF6A1B9A.toInt() to 5, // 보라 (예전 보강 색)
        0xFFC62828.toInt() to 6, // 빨강 → 분홍
        0xFFEF6C00.toInt() to 0, // 주황 → 올리브
        0xFF00838F.toInt() to 2, // 청록
        0xFF4E342E.toInt() to 0, // 갈색 → 올리브
        0xFF37474F.toInt() to 7, // 청회색 → 회색
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

/** 지금 테마에 맞는 (컨테이너, 글자) 색. */
@Composable
@ReadOnlyComposable
fun CourseColor.tones(): Pair<Color, Color> =
    if (LocalAppColors.current.isDark) Color(containerDark) to Color(onContainerDark)
    else Color(container) to Color(onContainer)

/** 작은 점·칩 앞 표시용. */
@Composable
@ReadOnlyComposable
fun CourseColor.accent(): Color =
    if (LocalAppColors.current.isDark) Color(accentDark) else Color(seed)
