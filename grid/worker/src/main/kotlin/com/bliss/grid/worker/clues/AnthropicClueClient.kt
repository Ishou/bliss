// Anthropic-backed [ClueClient] adapter (ADR-0013 §5).
package com.bliss.grid.worker.clues

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import com.bliss.grid.domain.clue.ClueClient
import com.bliss.grid.domain.clue.ClueResult
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
    ): ClueResult =
        withContext(Dispatchers.IO) {
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
                            return@withContext ClueResult.ApiError(IllegalStateException("Anthropic response had no text block"))
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
                if (text.length <= MAX_CLUE_CHARS) ClueResult.Accepted(text) else ClueResult.TooLong(text)
            } catch (e: Exception) {
                span.recordException(e)
                span.setStatus(StatusCode.ERROR)
                ClueResult.ApiError(e)
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
