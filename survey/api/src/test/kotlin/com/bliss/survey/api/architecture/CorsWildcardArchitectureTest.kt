package com.bliss.survey.api.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test

// Guards ADR-0048: api Modules pairing install(CORS) with allowCredentials = true MUST use allowHeaders { true }.
class CorsWildcardArchitectureTest {
    @Test
    fun `credentialed CORS in api modules uses the wildcard headers predicate`() {
        // Path-glob lets the guard catch future api contexts without per-context edits.
        val apiMainFiles =
            Konsist
                .scopeFromProject()
                .files
                .filter { it.path.contains("/api/src/main/kotlin/") }

        val offenders = mutableListOf<String>()
        for (file in apiMainFiles) {
            val blocks = extractCorsConfigBlocks(file.text)
            for ((label, body) in blocks) {
                if (!body.contains("allowCredentials = true")) continue
                if (containsWildcardHeadersPredicate(body)) continue
                offenders.add("${file.path} ($label)")
            }
        }

        if (offenders.isNotEmpty()) {
            val message =
                buildString {
                    appendLine(
                        "ADR-0048: credentialed CORS (allowCredentials = true) must use the wildcard predicate " +
                            "`allowHeaders { true }`, not an explicit allowHeader(...) list. See ADR-0048.",
                    )
                    appendLine("Offending file(s):")
                    offenders.forEach { appendLine("  - $it") }
                }
            throw AssertionError(message)
        }
    }

    private data class CorsBlock(
        val label: String,
        val body: String,
    )

    private fun extractCorsConfigBlocks(source: String): List<CorsBlock> {
        val results = mutableListOf<CorsBlock>()
        results.addAll(findBalancedBraceBlocks(source, INSTALL_CORS_REGEX, label = "install(CORS)"))
        results.addAll(findBalancedBraceBlocks(source, INSTALL_CORS_HELPER_REGEX, label = "install*Cors* helper"))
        return results
    }

    // Depth-counts braces so nested lambdas (StatusPages catches, etc.) don't terminate the block early.
    private fun findBalancedBraceBlocks(
        source: String,
        headerRegex: Regex,
        label: String,
    ): List<CorsBlock> {
        val results = mutableListOf<CorsBlock>()
        for (match in headerRegex.findAll(source)) {
            val openIdx = source.indexOf('{', startIndex = match.range.last + 1)
            if (openIdx < 0) continue
            var depth = 1
            var i = openIdx + 1
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth == 0) break
                i++
            }
            if (depth == 0) {
                results.add(CorsBlock(label = label, body = source.substring(openIdx + 1, i)))
            }
        }
        return results
    }

    // Ktor formats vary slightly across modules; tolerate whitespace around the lambda and braces.
    private fun containsWildcardHeadersPredicate(body: String): Boolean = WILDCARD_HEADERS_REGEX.containsMatchIn(body)

    companion object {
        private val INSTALL_CORS_REGEX = Regex("""\binstall\s*\(\s*CORS\s*\)\s*""")

        // Matches `fun Application.installXyzCors(...)` / `fun Application.installSurveyCors(...)`.
        private val INSTALL_CORS_HELPER_REGEX =
            Regex("""\bfun\s+(?:[\w.]+\.)?install[A-Za-z0-9_]*Cors[A-Za-z0-9_]*\s*\([^)]*\)\s*""")

        private val WILDCARD_HEADERS_REGEX = Regex("""\ballowHeaders\s*\{\s*true\s*\}""")
    }
}
