// Anthropic-backed clue generator (ADR-0013 §5). One method, one trade-off:
// length check is the client's responsibility, not the caller's, so the caller
// can branch on Accepted / TooLong / ApiError without re-measuring chars.
package com.bliss.grid.worker.clues

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import org.slf4j.LoggerFactory

/** Result of one [ClueClient.generateClue] call. */
internal sealed class ClueResult {
    /** Model returned a clue ≤ [MAX_CLUE_CHARS]. */
    data class Accepted(
        val clue: String,
    ) : ClueResult()

    /** Model returned a clue > [MAX_CLUE_CHARS]; caller decides whether to retry. */
    data class TooLong(
        val rejectedClue: String,
    ) : ClueResult()

    /** Anthropic call failed (network, 4xx/5xx, malformed response). */
    data class ApiError(
        val cause: Throwable,
    ) : ClueResult()
}

/** Production impl wraps the Anthropic SDK; tests provide a fake. */
internal interface ClueClient {
    /**
     * Generate one clue for [word]. If [retry] is true, the user message uses the stricter
     * re-prompt template (ADR-0013 §5); otherwise the standard "Mot : <word>" message.
     * The system prompt + few-shot prefix is identical across all calls in a run — that's
     * the whole point of prompt caching.
     */
    suspend fun generateClue(
        word: String,
        retry: Boolean = false,
    ): ClueResult
}

/**
 * Default [ClueClient]. Pinned to Claude Haiku 4.5 (ADR-0013 §5): plenty of headroom for
 * ≤18-char French clues at the right cost for hundreds of thousands of rows. Bumping the
 * model is a code change with an ADR.
 */
internal class AnthropicClueClient(
    private val client: AnthropicClient,
) : ClueClient {
    private val log = LoggerFactory.getLogger(AnthropicClueClient::class.java)
    private val tracer = GlobalOpenTelemetry.getTracer("com.bliss.grid.worker")

    override suspend fun generateClue(
        word: String,
        retry: Boolean,
    ): ClueResult {
        val userText = if (retry) retryMessage(word) else userMessage(word)
        val params =
            MessageCreateParams
                .builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                // System prompt + few-shot are constant across the whole run — cache the
                // prefix so every call after the first is mostly a cache read. Verify hits
                // via response.usage().cacheReadInputTokens() (DEBUG-logged below).
                .systemOfTextBlockParams(
                    listOf(
                        TextBlockParam
                            .builder()
                            .text(SYSTEM_PROMPT + "\n\n" + fewShotBlock())
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build(),
                    ),
                ).addUserMessage(userText)
                .build()

        val span =
            tracer
                .spanBuilder("anthropic.messages.create")
                .setAttribute("model", MODEL)
                .startSpan()
        try {
            val response = client.messages().create(params)
            val text =
                response
                    .content()
                    .stream()
                    .flatMap { it.text().stream() }
                    .map { it.text() }
                    .findFirst()
                    .orElse(null)
                    ?.trim()
                    ?: run {
                        span.setStatus(StatusCode.ERROR)
                        return ClueResult.ApiError(IllegalStateException("Anthropic response had no text block"))
                    }

            val usage = response.usage()
            log.debug(
                "clue_call model={} input_tokens={} cached_tokens={} output_tokens={} clue_chars={}",
                MODEL,
                usage.inputTokens(),
                usage.cacheReadInputTokens().orElse(0L),
                usage.outputTokens(),
                text.length,
            )

            span.setStatus(StatusCode.OK)
            return if (text.length <= MAX_CLUE_CHARS) ClueResult.Accepted(text) else ClueResult.TooLong(text)
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR)
            return ClueResult.ApiError(e)
        } finally {
            span.end()
        }
    }

    companion object {
        // Pinned (ADR-0013 §5). Haiku 4.5 dated form; bumping is an ADR-class change.
        internal const val MODEL: String = "claude-haiku-4-5-20251001"

        // 18-char clue + a tiny ceiling for whitespace/junk Claude might prepend.
        private const val MAX_TOKENS: Long = 64
    }
}

/**
 * Fail-fast factory: read [API_KEY_ENV] at startup, blow up before the first row if it's
 * unset. We do not want to spend an hour processing rows only to discover the key is bad.
 */
internal object ClueClientFactory {
    internal const val API_KEY_ENV: String = "ANTHROPIC_API_KEY"

    fun fromEnv(baseUrl: String? = null): ClueClient {
        val apiKey =
            System.getenv(API_KEY_ENV)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "$API_KEY_ENV is required for generate-clues; refusing to start without it",
                )
        val builder = AnthropicOkHttpClient.builder().apiKey(apiKey)
        if (baseUrl != null) builder.baseUrl(baseUrl)
        return AnthropicClueClient(builder.build())
    }
}
