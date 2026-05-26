package com.bliss.survey.infrastructure.identity

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

class IdentityClientTest {
    private val userId = UUID.fromString("00000000-0000-7000-8000-000000000001")

    @Test
    fun `verifySession returns user id on 200 OK`() =
        runTest {
            val capturedCookies = mutableListOf<String?>()
            val capturedPaths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    capturedCookies += request.headers["Cookie"]
                    capturedPaths += request.url.encodedPath
                    respond(
                        content = """{"userId":"$userId","displayName":"Alice"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val client = IdentityClient(baseUrl = "https://identity.example", engine = engine)
            val resolved = client.verifySession("session-cookie-value")
            assertThat(resolved).isEqualTo(userId)
            assertThat(capturedCookies.size).isEqualTo(1)
            assertThat(capturedCookies[0]).isNotNull()
            assertThat(capturedCookies[0]!!.contains("__Secure-ws_session=session-cookie-value")).isEqualTo(true)
            // identity-api's session-verify is at /v1/auth/whoami; grid/game call the same path.
            assertThat(capturedPaths).isEqualTo(listOf("/v1/auth/whoami"))
            client.close()
        }

    @Test
    fun `verifySession returns null on 401`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"type":"about:blank","status":401,"title":"unauthorised"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val client = IdentityClient(baseUrl = "https://identity.example", engine = engine)
            assertThat(client.verifySession("expired")).isNull()
            client.close()
        }

    @Test
    fun `verifySession short-circuits on blank cookie`() =
        runTest {
            val callCount = intArrayOf(0)
            val engine =
                MockEngine { _ ->
                    callCount[0] += 1
                    respond("", HttpStatusCode.OK)
                }
            val client = IdentityClient(baseUrl = "https://identity.example", engine = engine)
            assertThat(client.verifySession(null)).isNull()
            assertThat(client.verifySession("")).isNull()
            assertThat(client.verifySession("   ")).isNull()
            assertThat(callCount[0]).isEqualTo(0)
            client.close()
        }

    @Test
    fun `verifySession returns null when the body is malformed`() =
        runTest {
            val engine =
                MockEngine { _ ->
                    respond(
                        content = """{"userId":"not-a-uuid"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            val client = IdentityClient(baseUrl = "https://identity.example", engine = engine)
            assertThat(client.verifySession("good-cookie")).isNull()
            client.close()
        }
}
