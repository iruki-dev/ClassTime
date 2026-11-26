package dev.iruki.classtime.ui.common

/**
 * 한글 낱말이 줄 끝에서 갈라지지 않게(‘정렬/까지’) 한글 글자 사이에 단어 연결자(U+2060)를 넣는다.
 * CSS 의 `word-break: keep-all` 과 같은 효과로, 띄어쓰기에서만 줄을 바꾼다. 한 낱말이 한 줄보다
 * 길면 그때는 낱말 안에서 끊긴다. 화면에 보이는 글에만 쓰고, 복사·검색할 글에는 쓰지 않는다.
 */
fun String.keepAll(): String {
    if (length < 2) return this
    val out = StringBuilder(length + length / 2)
    for (i in indices) {
        val ch = this[i]
        if (i > 0 && joins(this[i - 1], ch)) out.append(WORD_JOINER)
        out.append(ch)
    }
    return out.toString()
}

private const val WORD_JOINER = '⁠'

private fun hangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

/** 한글과 그 앞뒤에 붙은 글자(숫자·영문·괄호 등)는 한 낱말로 묶는다. 공백·대시에서는 끊을 수 있다. */
private fun joins(a: Char, b: Char): Boolean {
    if (a.isWhitespace() || b.isWhitespace()) return false
    if (a in BREAKABLE || b in BREAKABLE) return false
    return hangul(a) || hangul(b)
}

private const val BREAKABLE = "—–-/·"
