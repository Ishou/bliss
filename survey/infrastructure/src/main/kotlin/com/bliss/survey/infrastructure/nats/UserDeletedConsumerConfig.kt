package com.bliss.survey.infrastructure.nats

import io.nats.client.Connection
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import java.time.Duration

/** Single source of truth for the survey-api's JetStream durable consumer.
 * The bootstrap path (`survey-worker --bootstrap-consumer`, run by the chart's
 * pre-install/pre-upgrade Job) and the api pod's bind path
 * ([UserDeletedConsumer]) both reference these constants so the durable
 * config can never drift between create-time and bind-time.
 */
object UserDeletedConsumerConfig {
    const val SUBJECT: String = "wordsparrow.user.deleted"
    const val STREAM_NAME: String = "WORDSPARROW_USER_EVENTS"
    const val DURABLE_NAME: String = "survey-api-user-deleted"

    // Stable deliver subject derived from the durable name. The bootstrap Job
    // passes this verbatim to `addOrUpdateConsumer`; the api pod fetches it
    // back via `bind(true)`. Keeping it deterministic means re-running the
    // Job on helm upgrade is a no-op (same config → idempotent).
    const val DELIVER_SUBJECT: String = "_DELIVER.survey-api.user-deleted"

    fun consumerConfiguration(): ConsumerConfiguration =
        ConsumerConfiguration
            .builder()
            .durable(DURABLE_NAME)
            .filterSubject(SUBJECT)
            .ackPolicy(AckPolicy.Explicit)
            .ackWait(Duration.ofSeconds(30))
            .maxDeliver(5)
            .deliverSubject(DELIVER_SUBJECT)
            .build()

    /** Create-or-update the durable consumer. Idempotent for matching configs. */
    fun bootstrap(nats: Connection) {
        nats.jetStreamManagement().addOrUpdateConsumer(STREAM_NAME, consumerConfiguration())
    }
}
