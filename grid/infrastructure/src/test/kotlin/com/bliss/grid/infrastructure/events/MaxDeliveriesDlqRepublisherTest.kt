package com.bliss.grid.infrastructure.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.nats.client.Connection
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamManagement
import io.nats.client.api.Error
import io.nats.client.impl.Headers
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class MaxDeliveriesDlqRepublisherTest {
    @Test
    fun `publishes placeholder dlq entry when original message has been purged`() {
        val captured = mutableListOf<PublishCall>()
        val connection = stubConnection(captured)
        val jsm = stubJetStreamManagementThatThrowsOnGetMessage()

        val republisher =
            MaxDeliveriesDlqRepublisher(
                connection = connection,
                jetStreamManagement = jsm,
                streamName = "WORDSPARROW_USER_EVENTS",
                consumerNames = listOf("grid-user-deleted"),
            )

        val advisoryJson =
            """
            {
              "stream": "WORDSPARROW_USER_EVENTS",
              "consumer": "grid-user-deleted",
              "stream_seq": 42,
              "subject": "wordsparrow.user.deleted",
              "deliveries": 5
            }
            """.trimIndent()

        republisher.handleAdvisory(advisoryJson.toByteArray(Charsets.UTF_8))

        val call = captured.single()
        assertThat(call.subject).isEqualTo("wordsparrow.dlq.wordsparrow.user.deleted")
        assertThat(call.body.size).isEqualTo(0)
        assertThat(call.headers).isNotNull()
        val headers = call.headers!!
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_PAYLOAD_MISSING))
            .isEqualTo("true")
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_ORIGINAL_STREAM))
            .isEqualTo("WORDSPARROW_USER_EVENTS")
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_ORIGINAL_SEQ))
            .isEqualTo("42")
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_CONSUMER))
            .isEqualTo("grid-user-deleted")
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_DELIVERIES))
            .isEqualTo("5")
        assertThat(headers.getFirst(MaxDeliveriesDlqRepublisher.HEADER_FAILED_AT))
            .isNotNull()
    }

    private data class PublishCall(
        val subject: String,
        val headers: Headers?,
        val body: ByteArray,
    )

    private fun stubConnection(captured: MutableList<PublishCall>): Connection {
        val handler =
            InvocationHandler { _: Any?, method: Method, args: Array<Any?>? ->
                if (method.name == "publish" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.parameterTypes[1] == Headers::class.java &&
                    method.parameterTypes[2] == ByteArray::class.java
                ) {
                    @Suppress("UNCHECKED_CAST")
                    captured.add(
                        PublishCall(
                            subject = args!![0] as String,
                            headers = args[1] as Headers?,
                            body = args[2] as ByteArray,
                        ),
                    )
                    return@InvocationHandler null
                }
                defaultReturnFor(method.returnType)
            }
        return Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }

    private fun stubJetStreamManagementThatThrowsOnGetMessage(): JetStreamManagement {
        val handler =
            InvocationHandler { _: Any?, method: Method, _: Array<Any?>? ->
                if (method.name == "getMessage") {
                    throw JetStreamApiException(Error.JsNoMessageFoundErr)
                }
                defaultReturnFor(method.returnType)
            }
        return Proxy.newProxyInstance(
            JetStreamManagement::class.java.classLoader,
            arrayOf(JetStreamManagement::class.java),
            handler,
        ) as JetStreamManagement
    }

    private fun defaultReturnFor(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
}
