package dev.iruki.classtime.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PaymentRequiredException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import java.io.IOException
import java.time.Duration

/** 요약에 쓸 수 있는 모델과 표준 요금(백만 토큰당 달러). 배치는 이 값의 절반이다. */
enum class SummaryModel(val id: String, private val inPerM: Double, private val outPerM: Double) {
    OPUS("claude-opus-5-5", 4.0, 20.0),
    SONNET("claude-sonnet-5-5", 2.0, 10.0),
    HAIKU("claude-haiku-5-5", 0.10, 0.50);

    /** 배치(50% 할인)로 보낸 요청 하나의 요금(달러). Haiku 는 입력 10만 토큰을 넘으면 비싸진다. */
    fun batchCost(inputTokens: Long, outputTokens: Long): Double {
        val long = this == HAIKU && inputTokens > 100_000
        val i = if (long) 0.50 else inPerM
        val o = if (long) 2.50 else outPerM
        return (inputTokens * i + outputTokens * o) / 1_000_000.0 * 0.5
    }

    /**
     * 녹음 길이만으로 어림한 요약 요금(달러, 배치). 강의 1시간 ≈ 입력 2만 · 출력 4천 토큰
     * (지시문 포함, 한국어 강의의 말 빠르기로 어림한 값). 확인 창의 ‘약’ 금액에만 쓴다.
     */
    fun estimate(audioMs: Long): Double {
        val hours = audioMs / 3_600_000.0
        return batchCost((IN_PER_HOUR * hours).toLong() + SYSTEM_TOKENS, (OUT_PER_HOUR * hours).toLong())
    }

    companion object {
        val DEFAULT = OPUS
        private const val IN_PER_HOUR = 18_000L
        private const val SYSTEM_TOKENS = 2_000L
        private const val OUT_PER_HOUR = 4_000L
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 배치에 넣는 요약 요청 하나. */
data class SummaryRequest(val customId: String, val model: String, val system: String, val user: String)

/** 배치에서 돌아온 요약 하나의 결과. */
sealed interface SummaryOutcome {
    data class Success(val text: String, val inputTokens: Long, val outputTokens: Long) : SummaryOutcome
    /** 출력이 잘렸다(max_tokens). */
    data class Truncated(val inputTokens: Long, val outputTokens: Long) : SummaryOutcome
    /** 안전 분류가 거절했다. 배치에는 서버 측 대체 모델(fallbacks)을 쓸 수 없다. */
    data class Refused(val category: String) : SummaryOutcome
    data class Errored(val billing: Boolean, val message: String) : SummaryOutcome
    data object Expired : SummaryOutcome
    data object Canceled : SummaryOutcome
}

class SummaryApiException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** 키가 없거나 거부됨(401/403). */
        AUTH,
        /** 잔액 부족 등 결제 문제. */
        BILLING,
        /** 요청 한도(429). 잠시 뒤 다시. */
        RATE_LIMIT,
        /** 5xx·과부하. 잠시 뒤 다시. */
        SERVER,
        /** 연결 안 됨. 연결되면 다시. */
        NETWORK,
        /** 그 밖의 4xx. 다시 해도 같다. */
        BAD_REQUEST,
    }
}

/** 요약 엔진이 바깥과 만나는 곳. 실제: [ClaudeClient]. */
interface SummaryApi {
    /** 키가 쓸 수 있는지. 모델 정보 조회라 토큰을 쓰지 않는다. */
    fun verify(key: String)
    /** 요청들을 배치 하나로 보낸다. 배치 id. */
    fun submit(key: String, requests: List<SummaryRequest>): String
    /** 배치가 끝났는지(결과를 받을 수 있는지). */
    fun ended(key: String, batchId: String): Boolean
    /** 끝난 배치의 결과. custom_id → 결과. */
    fun results(key: String, batchId: String): Map<String, SummaryOutcome>
}

/**
 * Claude API(공식 Java SDK). 요약은 실시간일 필요가 없어 Message Batches 로 보낸다(요금 50%).
 * 구조화 출력으로 JSON 을 받고, 생각은 모델 기본값(적응형)에 맡기되 effort 는 medium 으로 둔다.
 */
class ClaudeClient(private val baseUrl: String? = null) : SummaryApi {

    override fun verify(key: String) {
        call(key) { it.models().retrieve(SummaryModel.DEFAULT.id) }
    }

    override fun submit(key: String, requests: List<SummaryRequest>): String = call(key) { client ->
        val schema = JsonOutputFormat.Schema.builder().apply {
            SummaryPrompt.schema().forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
        }.build()
        val params = BatchCreateParams.builder().apply {
            requests.forEach { r ->
                addRequest(
                    BatchCreateParams.Request.builder()
                        .customId(r.customId)
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(r.model)
                                .maxTokens(MAX_TOKENS)
                                .system(r.system)
                                .addUserMessage(r.user)
                                .outputConfig(
                                    OutputConfig.builder()
                                        .effort(OutputConfig.Effort.MEDIUM)
                                        .format(JsonOutputFormat.builder().schema(schema).build())
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
            }
        }.build()
        client.messages().batches().create(params).id()
    }

    override fun ended(key: String, batchId: String): Boolean = call(key) { client ->
        client.messages().batches().retrieve(batchId).processingStatus() == MessageBatch.ProcessingStatus.ENDED
    }

    override fun results(key: String, batchId: String): Map<String, SummaryOutcome> = call(key) { client ->
        val out = mutableMapOf<String, SummaryOutcome>()
        client.messages().batches().resultsStreaming(batchId).use { stream ->
            stream.stream().forEach { r ->
                val res = r.result()
                out[r.customId()] = when {
                    res.isSucceeded() -> {
                        val m = res.succeeded().get().message()
                        val usage = m.usage()
                        when (m.stopReason().orElse(null)) {
                            StopReason.REFUSAL -> SummaryOutcome.Refused(
                                m.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("")
                            )
                            StopReason.MAX_TOKENS -> SummaryOutcome.Truncated(usage.inputTokens(), usage.outputTokens())
                            else -> SummaryOutcome.Success(
                                m.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString(""),
                                usage.inputTokens(),
                                usage.outputTokens(),
                            )
                        }
                    }
                    res.isErrored() -> {
                        val e = res.errored().get().error().error()
                        val msg = runCatching { e.toString() }.getOrDefault("")
                        SummaryOutcome.Errored(billing = msg.contains("credit", ignoreCase = true) || msg.contains("billing", ignoreCase = true), msg)
                    }
                    res.isExpired() -> SummaryOutcome.Expired
                    else -> SummaryOutcome.Canceled
                }
            }
        }
        out
    }

    private fun <T> call(key: String, block: (AnthropicClient) -> T): T {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .apply { baseUrl?.let { baseUrl(it) } }
            .timeout(Duration.ofSeconds(60))
            .maxRetries(2)
            .build()
        try {
            return block(client)
        } catch (e: UnauthorizedException) {
            throw SummaryApiException(SummaryApiException.Kind.AUTH, "401", e)
        } catch (e: PermissionDeniedException) {
            throw SummaryApiException(SummaryApiException.Kind.AUTH, "403", e)
        } catch (e: PaymentRequiredException) {
            throw SummaryApiException(SummaryApiException.Kind.BILLING, "402", e)
        } catch (e: RateLimitException) {
            throw SummaryApiException(SummaryApiException.Kind.RATE_LIMIT, "429", e)
        } catch (e: OverloadedException) {
            throw SummaryApiException(SummaryApiException.Kind.SERVER, "overloaded", e)
        } catch (e: InternalServerException) {
            throw SummaryApiException(SummaryApiException.Kind.SERVER, "5xx", e)
        } catch (e: BadRequestException) {
            val billing = e.message?.contains("credit", ignoreCase = true) == true
            throw SummaryApiException(if (billing) SummaryApiException.Kind.BILLING else SummaryApiException.Kind.BAD_REQUEST, e.message ?: "400", e)
        } catch (e: NotFoundException) {
            throw SummaryApiException(SummaryApiException.Kind.BAD_REQUEST, "404", e)
        } catch (e: AnthropicServiceException) {
            val kind = if (e.statusCode() >= 500) SummaryApiException.Kind.SERVER else SummaryApiException.Kind.BAD_REQUEST
            throw SummaryApiException(kind, "${e.statusCode()}", e)
        } catch (e: AnthropicIoException) {
            throw SummaryApiException(SummaryApiException.Kind.NETWORK, e.message ?: "io", e)
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        /** 3시간 강의의 촘촘한 요약과 생각까지 담고도 남는 크기. 배치라 시간 초과 걱정이 없다. */
        const val MAX_TOKENS = 32_000L
    }
}
