package dev.iruki.classtime.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Architecture
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Engineering
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.vector.ImageVector

/** 과목 아이콘 하나. [key] 가 DB(Course.icon)에 저장된다. */
data class CourseIcon(val key: String, val vector: ImageVector)

/**
 * 과목 아이콘 모음. 노션의 페이지 아이콘처럼 미리 정해 둔 것 중에서 고른다.
 *
 * 과목명 첫 글자를 쓰던 아바타는 글자마다 모양이 들쭉날쭉하고, 같은 글자로 시작하는
 * 과목(‘자료구조’, ‘자연과학’)을 구별하지 못했다. 아이콘은 모양이 일정하고 뜻이 바로 읽힌다.
 * 색은 넣지 않는다 — 아이콘 타일은 무채색, 색은 시간표 블록이 맡는다.
 */
object CourseIcons {

    val all: List<CourseIcon> = listOf(
        CourseIcon("book", Icons.AutoMirrored.Rounded.MenuBook),
        CourseIcon("school", Icons.Rounded.School),
        CourseIcon("lightbulb", Icons.Rounded.Lightbulb),
        CourseIcon("functions", Icons.Rounded.Functions),
        CourseIcon("calculate", Icons.Rounded.Calculate),
        CourseIcon("stats", Icons.Rounded.QueryStats),
        CourseIcon("code", Icons.Rounded.Code),
        CourseIcon("computer", Icons.Rounded.Computer),
        CourseIcon("memory", Icons.Rounded.Memory),
        CourseIcon("science", Icons.Rounded.Science),
        CourseIcon("biotech", Icons.Rounded.Biotech),
        CourseIcon("eco", Icons.Rounded.Eco),
        CourseIcon("medical", Icons.Rounded.MedicalServices),
        CourseIcon("psychology", Icons.Rounded.Psychology),
        CourseIcon("translate", Icons.Rounded.Translate),
        CourseIcon("history", Icons.Rounded.HistoryEdu),
        CourseIcon("forum", Icons.Rounded.Forum),
        CourseIcon("public", Icons.Rounded.Public),
        CourseIcon("gavel", Icons.Rounded.Gavel),
        CourseIcon("bank", Icons.Rounded.AccountBalance),
        CourseIcon("business", Icons.Rounded.BusinessCenter),
        CourseIcon("engineering", Icons.Rounded.Engineering),
        CourseIcon("architecture", Icons.Rounded.Architecture),
        CourseIcon("rocket", Icons.Rounded.RocketLaunch),
        CourseIcon("palette", Icons.Rounded.Palette),
        CourseIcon("draw", Icons.Rounded.Draw),
        CourseIcon("camera", Icons.Rounded.PhotoCamera),
        CourseIcon("theater", Icons.Rounded.TheaterComedy),
        CourseIcon("music", Icons.Rounded.MusicNote),
        CourseIcon("sports", Icons.Rounded.SportsBasketball),
    )

    val default: CourseIcon = all.first()

    private val byKey = all.associateBy { it.key }

    /**
     * 과목명으로 짐작할 때 확인하는 순서. **순서가 중요하다** — 더 구체적인 것이 앞에 와야
     * ‘컴퓨터프로그래밍’이 컴퓨터가 아니라 코드로, ‘데이터베이스’가 통계가 아니라 컴퓨터로,
     * ‘문법’이 법이 아니라 언어로 잡힌다.
     */
    private val rules: List<Pair<String, List<String>>> = listOf(
        "code" to listOf("프로그래밍", "코딩", "자료구조", "알고리즘", "소프트웨어", "파이썬", "자바", "웹", "앱개발",
            "programming", "algorithm", "software", "python", "java", "coding"),
        "memory" to listOf("회로", "전자", "반도체", "디지털논리", "컴퓨터구조", "임베디드", "circuit", "electronic"),
        "computer" to listOf("컴퓨터", "운영체제", "네트워크", "데이터베이스", "인공지능", "머신러닝", "보안",
            "computer", "network", "database", "operating"),
        "stats" to listOf("통계", "확률", "데이터", "statistic", "probability", "data"),
        "functions" to listOf("수학", "미적분", "선형대수", "미분", "적분", "해석학", "대수", "기하", "이산",
            "math", "calculus", "algebra"),
        "calculate" to listOf("회계", "재무", "세무", "accounting", "finance"),
        "biotech" to listOf("생명", "생물", "유전", "미생물", "biology", "genetic"),
        "medical" to listOf("의학", "간호", "약학", "해부", "보건", "medic", "nursing", "anatomy"),
        "science" to listOf("화학", "물리", "실험", "과학", "chemistry", "physics", "science"),
        "eco" to listOf("환경", "생태", "지구", "environment", "ecology"),
        "psychology" to listOf("심리", "psychology"),
        "translate" to listOf("영어", "일본어", "중국어", "독일어", "프랑스어", "스페인어", "외국어", "회화", "문법",
            "언어", "english", "language", "japanese", "chinese"),
        "history" to listOf("역사", "한국사", "세계사", "사학", "history"),
        "forum" to listOf("토론", "글쓰기", "작문", "발표", "커뮤니케이션", "스피치", "writing", "communication"),
        "gavel" to listOf("법학", "헌법", "민법", "형법", "상법", "법률", "행정법", "law"),
        "business" to listOf("경영", "마케팅", "비즈니스", "business", "marketing", "management"),
        "bank" to listOf("정치", "행정", "경제", "사회", "politic", "economic", "sociology"),
        "lightbulb" to listOf("철학", "윤리", "사고", "교양", "philosophy", "ethics"),
        "book" to listOf("문학", "국어", "독서", "고전", "literature", "reading"),
        "palette" to listOf("미술", "색채", "회화실기", "art"),
        "draw" to listOf("디자인", "드로잉", "스케치", "design", "drawing"),
        "camera" to listOf("사진", "영상", "미디어", "photo", "video", "media"),
        "theater" to listOf("연극", "영화", "공연", "theater", "cinema", "film"),
        "music" to listOf("음악", "화성학", "작곡", "합창", "music"),
        "sports" to listOf("체육", "운동", "스포츠", "요가", "sport", "fitness"),
        "architecture" to listOf("건축", "도시", "architecture"),
        "rocket" to listOf("항공", "우주", "창업", "aerospace", "startup"),
        "engineering" to listOf("공학", "기계", "역학", "engineering", "mechanic"),
        "public" to listOf("지리", "국제", "글로벌", "geography", "global"),
        "school" to listOf("세미나", "교육", "진로", "캡스톤", "education", "seminar"),
    )

    /** 과목명에서 아이콘을 짐작한다. 맞는 것이 없으면 책. */
    fun guess(subject: String): CourseIcon {
        val name = subject.lowercase().replace(" ", "")
        val key = rules.firstOrNull { (_, words) -> words.any { name.contains(it) } }?.first
        return key?.let(byKey::get) ?: default
    }

    /** 저장된 키로 찾는다. 비었거나 모르는 키(예전 버전·삭제된 아이콘)면 과목명으로 짐작한다. */
    fun of(key: String, subject: String): CourseIcon =
        byKey[key] ?: guess(subject)
}
