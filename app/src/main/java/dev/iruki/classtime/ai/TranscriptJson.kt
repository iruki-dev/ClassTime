package dev.iruki.classtime.ai

import org.json.JSONArray
import org.json.JSONObject

/** Whisper 가 받아 적은 한 문장. 시간은 녹음 처음부터의 ms. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** 읽기 좋게 묶은 한 문단. [startMs] 는 문단 첫 문장의 시작. */
data class Paragraph(val startMs: Long, val text: String)

/** [dev.iruki.classtime.data.Transcript] 의 JSON 칸을 읽고 쓴다. 키는 짧게(용량). */
object TranscriptJson {

    /** 조각 계획. `[{"p":[[시작,끝],…],"r":1}, …]` (r 은 다시 받아 적는 조각만). */
    fun plan(chunks: List<Chunk>): String =
        JSONArray(
            chunks.map { c ->
                JSONObject().put("p", JSONArray(c.pieces.map { JSONArray(listOf(it.first, it.last)) }))
                    .apply { if (c.retry) put("r", 1) }
            }
        ).toString()

    /** 예전 형식(`[[시작,끝],…]`, 조각마다 구간 하나)도 읽는다. */
    fun plan(json: String): List<Chunk> {
        if (json.isBlank()) return emptyList()
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val o = a.optJSONObject(i)
            if (o == null) {
                Chunk(listOf(a.getJSONArray(i).let { it.getLong(0)..it.getLong(1) }))
            } else {
                val p = o.getJSONArray("p")
                Chunk(
                    pieces = (0 until p.length()).map { k -> p.getJSONArray(k).let { it.getLong(0)..it.getLong(1) } },
                    retry = o.optInt("r") == 1,
                )
            }
        }
    }

    fun segments(list: List<Segment>): String =
        JSONArray(list.map { JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text) }).toString()

    fun segments(json: String): List<Segment> {
        if (json.isBlank()) return emptyList()
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Segment(it.getLong("s"), it.getLong("e"), it.getString("t")) }
        }
    }

    fun paragraphs(list: List<Paragraph>): String =
        JSONArray(list.map { JSONObject().put("s", it.startMs).put("t", it.text) }).toString()

    fun paragraphs(json: String): List<Paragraph> {
        if (json.isBlank()) return emptyList()
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Paragraph(it.getLong("s"), it.getString("t")) }
        }
    }
}

/** 대본의 시각 표기. 1시간 미만은 mm:ss, 이상은 h:mm:ss. */
object Timestamps {
    fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
