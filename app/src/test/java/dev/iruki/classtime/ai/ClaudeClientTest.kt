package dev.iruki.classtime.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** 공식 SDK 가 실제로 보내고 읽는 모양을 가짜 서버로 확인한다(네트워크·토큰 없음). */
class ClaudeClientTest {

    private val server = MockWebServer()
    private val json = ObjectMapper()
    private lateinit var client: ClaudeClient
    private val requests = mutableListOf<RecordedRequest>()
    private var status = "in_progress"
    private var failWith: MockResponse? = null

    private fun batch(base: String) = """
        {"id":"msgbatch_1","type":"message_batch","processing_status":"$status",
         "request_counts":{"processing":0,"succeeded":2,"errored":1,"canceled":0,"expired":1},
         "ended_at":null,"created_at":"2026-11-26T00:00:00Z","expires_at":"2026-11-27T00:00:00Z",
         "archived_at":null,"cancel_initiated_at":null,
         "results_url":"${base}v1/messages/batches/msgbatch_1/results"}
    """.trimIndent()

    private fun message(stop: String, text: String, extra: String = "") = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":${json.writeValueAsString(text)}}],
         "stop_reason":"$stop","stop_sequence":null$extra,
         "usage":{"input_tokens":1200,"output_tokens":800}}
    """.trimIndent().replace("\n", "")

    private val results by lazy {
        listOf(
            """{"custom_id":"rec-1","result":{"type":"succeeded","message":${message("end_turn", "{\"title\":\"힙\"}")}}}""",
            """{"custom_id":"rec-2","result":{"type":"succeeded","message":${message("refusal", "", ",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"x\"}")}}}""",
            """{"custom_id":"rec-3","result":{"type":"succeeded","message":${message("max_tokens", "{\"title\":")}}}""",
            """{"custom_id":"rec-4","result":{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}}}""",
            """{"custom_id":"rec-5","result":{"type":"expired"}}""",
        ).joinToString("\n")
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                failWith?.let { return it }
                val base = server.url("/").toString()
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    request.method == "POST" && path == "/v1/messages/batches" -> ok(batch(base))
                    path == "/v1/messages/batches/msgbatch_1" -> ok(batch(base))
                    path == "/v1/messages/batches/msgbatch_1/results" ->
                        MockResponse().setHeader("content-type", "application/binary").setBody(results)
                    path.startsWith("/v1/models/") -> ok("""{"id":"claude-opus-5-5","type":"model","display_name":"Claude Opus 5.5","created_at":"2026-01-01T00:00:00Z"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        client = ClaudeClient(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)

    @Test
    fun submit_sendsOneBatchWithStructuredOutputAtMediumEffort() {
        val id = client.submit(
            "sk-ant-test",
            listOf(
                SummaryRequest("rec-1", "claude-opus-5-5", "SYS", "과목: 자료구조\n\n녹취록:\n[0] 힙"),
                SummaryRequest("rec-2", "claude-haiku-5-5", "SYS", "[0] 큐"),
            ),
        )
        assertThat(id).isEqualTo("msgbatch_1")
        val req = requests.single()
        // 키는 SDK 가 정한 방식(현재 Authorization: Bearer)으로 실린다.
        assertThat(listOf(req.getHeader("x-api-key"), req.getHeader("Authorization")?.removePrefix("Bearer "))).contains("sk-ant-test")
        val body = json.readTree(req.body.readUtf8())
        val items = body["requests"]
        assertThat(items.size()).isEqualTo(2)
        val p = items[0]["params"]
        assertThat(items[0]["custom_id"].asText()).isEqualTo("rec-1")
        assertThat(p["model"].asText()).isEqualTo("claude-opus-5-5")
        assertThat(p["max_tokens"].asLong()).isEqualTo(ClaudeClient.MAX_TOKENS)
        assertThat(p["system"].asText()).isEqualTo("SYS")
        assertThat(p["messages"][0]["role"].asText()).isEqualTo("user")
        assertThat(p["output_config"]["effort"].asText()).isEqualTo("medium")
        val format = p["output_config"]["format"]
        assertThat(format["type"].asText()).isEqualTo("json_schema")
        assertThat(format["schema"]["required"].map { it.asText() }).containsExactly("title", "overview", "notices", "sections", "terms")
        // 배치에는 서버 측 대체 모델을 쓸 수 없고, 생각 설정은 모델 기본값(적응형)에 맡긴다.
        assertThat(p.has("fallbacks")).isFalse()
        assertThat(p.has("thinking")).isFalse()
        assertThat(items[1]["params"]["model"].asText()).isEqualTo("claude-haiku-5-5")
    }

    @Test
    fun ended_followsProcessingStatus() {
        assertThat(client.ended("k", "msgbatch_1")).isFalse()
        status = "ended"
        assertThat(client.ended("k", "msgbatch_1")).isTrue()
    }

    @Test
    fun results_mapEveryOutcome() {
        status = "ended"
        val r = client.results("k", "msgbatch_1")
        assertThat(r["rec-1"]).isEqualTo(SummaryOutcome.Success("{\"title\":\"힙\"}", 1200, 800))
        assertThat(r["rec-2"]).isInstanceOf(SummaryOutcome.Refused::class.java)
        assertThat(r["rec-3"]).isEqualTo(SummaryOutcome.Truncated(1200, 800))
        assertThat((r["rec-4"] as SummaryOutcome.Errored).billing).isTrue()
        assertThat(r["rec-5"]).isEqualTo(SummaryOutcome.Expired)
    }

    @Test
    fun errors_areMappedToKinds() {
        client.verify("good")
        assertThat(requests.last().path).startsWith("/v1/models/claude-opus-5-5")

        failWith = MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        val auth = assertThrows(SummaryApiException::class.java) { client.verify("bad") }
        assertThat(auth.kind).isEqualTo(SummaryApiException.Kind.AUTH)

        failWith = MockResponse().setResponseCode(400).setHeader("content-type", "application/json")
            .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""")
        val billing = assertThrows(SummaryApiException::class.java) { client.submit("k", listOf(SummaryRequest("rec-1", "claude-opus-5-5", "S", "U"))) }
        assertThat(billing.kind).isEqualTo(SummaryApiException.Kind.BILLING)

        server.shutdown()
        val offline = assertThrows(SummaryApiException::class.java) { client.verify("k") }
        assertThat(offline.kind).isEqualTo(SummaryApiException.Kind.NETWORK)
    }
}
