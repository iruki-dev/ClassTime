package dev.iruki.classtime.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * ClassTime 색 체계.
 *
 * Google material-color-utilities(HCT)로 시드 #3D4FC4 에서 계산한 값이다.
 * primary H280·C60, secondary H280·C14, tertiary H340·C28, neutral H280·C4/C8.
 * 값을 손으로 고치지 말고, 바꿀 일이 있으면 같은 방식으로 다시 계산한다.
 *
 * 다이내믹 컬러는 쓰지 않는다. 준비됨(남색)·녹음 중(빨강)·확인 필요(노랑)는 의미가 있는
 * 색이라 기기 배경화면에 따라 바뀌면 안 되고, 적록 색각에서도 구분되도록 고른 쌍이다.
 */

internal val LightScheme = lightColorScheme(
    primary = Color(0xFF4153C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF2639AB),
    inversePrimary = Color(0xFFBBC3FF),
    secondary = Color(0xFF5C5D6F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E1F6),
    onSecondaryContainer = Color(0xFF444656),
    tertiary = Color(0xFF7B516F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F0),
    onTertiaryContainer = Color(0xFF613A57),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4153C4),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF3F0F4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FD),
    surfaceContainer = Color(0xFFF0EDF1),
    surfaceContainerHigh = Color(0xFFEAE7EC),
    surfaceContainerHighest = Color(0xFFE4E1E6),
    surfaceContainerLow = Color(0xFFF6F2F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCD9DE),
)

internal val DarkScheme = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF011B95),
    primaryContainer = Color(0xFF2639AB),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4153C4),
    secondary = Color(0xFFC5C5D9),
    onSecondary = Color(0xFF2E2F3F),
    secondaryContainer = Color(0xFF444656),
    onSecondaryContainer = Color(0xFFE1E1F6),
    tertiary = Color(0xFFEBB7DA),
    onTertiary = Color(0xFF48243F),
    tertiaryContainer = Color(0xFF613A57),
    onTertiaryContainer = Color(0xFFFFD7F0),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFBBC3FF),
    inverseSurface = Color(0xFFE4E1E6),
    inverseOnSurface = Color(0xFF303034),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393C),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF353438),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceDim = Color(0xFF131316),
)

/**
 * M3 색 역할에 없는 ClassTime 고유 역할.
 *
 * - [page]/[group]: 탐색 화면은 회색 바탕 위 흰 묶음(Android 16 설정과 같은 구성).
 * - record*: 마이크가 **실제로 녹음할 때만** 쓰는 빨강. 진한 채움은 이 상태 하나뿐이다.
 * - caution*: 사용자가 고쳐야 자동 녹음이 되는 상태. 녹음 빨강과 밝기로 구분되도록 늘 밝은
 *   컨테이너로만 쓴다(진한 노랑 채움은 적색맹에서 녹음 빨강과 거의 같아진다).
 */
@Immutable
data class AppColors(
    val page: Color,
    val group: Color,
    val record: Color,
    val onRecord: Color,
    val recordContainer: Color,
    val onRecordContainer: Color,
    val recordSubtle: Color,
    val onRecordSubtle: Color,
    val recordAccent: Color,
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val isDark: Boolean,
)

internal val LightAppColors = AppColors(
    page = Color(0xFFF0EDF1),
    group = Color(0xFFFFFFFF),
    record = Color(0xFFB91C13),
    onRecord = Color(0xFFFFFFFF),
    recordContainer = Color(0xFFFFDAD5),
    onRecordContainer = Color(0xFF410000),
    recordSubtle = Color(0xFFFFEDEA),
    onRecordSubtle = Color(0xFF930001),
    recordAccent = Color(0xFFB91C13),
    caution = Color(0xFF624000),
    cautionContainer = Color(0xFFFFDDB2),
    onCautionContainer = Color(0xFF291800),
    isDark = false,
)

internal val DarkAppColors = AppColors(
    page = Color(0xFF131316),
    group = Color(0xFF1F1F23),
    record = Color(0xFF930001),
    onRecord = Color(0xFFFFDAD5),
    recordContainer = Color(0xFF690001),
    onRecordContainer = Color(0xFFFFDAD5),
    recordSubtle = Color(0xFF3B0A07),
    onRecordSubtle = Color(0xFFFFB4A8),
    recordAccent = Color(0xFFFFB4A8),
    caution = Color(0xFFFFB94E),
    cautionContainer = Color(0xFF624000),
    onCautionContainer = Color(0xFFFFDDB2),
    isDark = true,
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
