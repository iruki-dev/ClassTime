package dev.iruki.classtime.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * M3 기본 타입 스케일 + 한글 보정.
 *
 * 글꼴은 기기 기본(Roboto + Noto Sans CJK / 제조사 글꼴)을 그대로 써서 사용자의 글꼴·크기
 * 설정을 존중한다. 한글은 획이 많아 같은 굵기에서 위계가 약해 보이므로 제목 역할만 굵기를
 * 600 으로 올렸다(KRDS 도 제목은 굵게 쓴다). 한글 문장은 14sp 이상, 12sp 는 라벨에만 쓴다.
 */
private val Base = Typography()

internal val AppTypography = Typography(
    displayLarge = Base.displayLarge,
    displayMedium = Base.displayMedium,
    displaySmall = Base.displaySmall,
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = Base.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = Base.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = Base.labelLarge,
    labelMedium = Base.labelMedium,
    labelSmall = Base.labelSmall,
)
