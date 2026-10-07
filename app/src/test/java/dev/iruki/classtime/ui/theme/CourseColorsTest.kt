package dev.iruki.classtime.ui.theme

import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Test

/** 순수 JVM 테스트 — 과목 색 이전과 대비 규칙. */
class CourseColorsTest {

    @Test
    fun paletteSeed_mapsToItself() {
        CourseColors.palette.forEach { assertThat(CourseColors.of(it.seed)).isEqualTo(it) }
    }

    @Test
    fun legacyColors_mapToIntendedNewColors() {
        val green = CourseColors.palette[3]
        val indigo = CourseColors.palette[6]
        val violet = CourseColors.palette[7]
        val grey = CourseColors.palette[9]
        assertThat(CourseColors.of(0xFF2E6C3E.toInt())).isEqualTo(green)
        assertThat(CourseColors.of(0xFF1B5E20.toInt())).isEqualTo(green) // 예전 Course 기본값
        assertThat(CourseColors.of(0xFF1565C0.toInt())).isEqualTo(indigo)
        assertThat(CourseColors.of(0xFF6A1B9A.toInt())).isEqualTo(violet) // 예전 보강 색
        assertThat(CourseColors.of(0xFF37474F.toInt())).isEqualTo(grey)
    }

    @Test
    fun v3Colors_keepTheirHue() {
        // v3 하늘(006685)로 저장된 과목은 새 팔레트에서도 하늘이어야 한다.
        assertThat(CourseColors.of(0xFF006685.toInt())).isEqualTo(CourseColors.palette[5])
        assertThat(CourseColors.of(0xFF636100.toInt())).isEqualTo(CourseColors.palette[2])
    }

    @Test
    fun legacyRed_neverBecomesARedCourseColor() {
        // 빨강은 녹음 전용이라 과목 색으로 남으면 안 된다.
        val mapped = CourseColors.of(0xFFC62828.toInt())
        val (hue, _) = CourseColors.hueSat(mapped.seed)
        assertThat(hue).isGreaterThan(300f)
    }

    @Test
    fun unknownColor_snapsToNearestHue_andGreysToGrey() {
        assertThat(CourseColors.of(0xFF00AAAA.toInt())).isEqualTo(CourseColors.palette[4]) // 청록
        assertThat(CourseColors.of(0xFF808080.toInt())).isEqualTo(CourseColors.palette.last())
    }

    @Test
    fun blockText_isReadable() {
        CourseColors.palette.forEach {
            // 과목명: 라이트는 AAA, 다크·보조 글자도 AA(4.5:1) 이상.
            assertThat(contrast(it.block, it.onBlock)).isAtLeast(7.0)
            assertThat(contrast(it.block, it.blockSub)).isAtLeast(4.5)
            assertThat(contrast(it.blockDark, it.onBlockDark)).isAtLeast(4.5)
        }
    }

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
