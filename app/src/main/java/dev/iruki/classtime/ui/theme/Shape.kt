package dev.iruki.classtime.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * M3 모서리 스케일. 화면 코드에서 임의의 dp 를 쓰지 말고 여기 이름을 쓴다.
 *  extraSmall 4  입력, 목록 안쪽 모서리
 *  small      8  칩, 시간표 칸
 *  medium    12  과목 아바타, 아이콘 타일
 *  large     16  FAB, 미니 플레이어
 *  extraLarge 28 상태 카드, 시트
 */
internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** M3 Expressive 분리형 목록: 바깥 20, 안쪽 4, 줄 사이 2. */
object GroupShapes {
    private val outer = 20.dp
    private val inner = 4.dp

    fun at(index: Int, count: Int) = when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(outer, outer, inner, inner)
        index == count - 1 -> RoundedCornerShape(inner, inner, outer, outer)
        else -> RoundedCornerShape(inner)
    }
}
