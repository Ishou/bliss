package com.bliss.survey.infrastructure.nats

import io.nats.client.Connection
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import java.time.Duration

/** Consumer constants shared by bootstrap Job and api bind path (ADR-0049). */
object UserDeletedConsumerConfig {
    const val SUBJECT: String = "wordsparrow.user.deleted"
    const val STREAM_NAME: String = "WORDSPARROW_USER_EVENTS"
    const val DURABLE_NAME: String = "survey-api-user-deleted"

    // Deterministic so addOrUpdateConsumer is idempotent across helm upgrades.
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

    /** Create-or-update the durable consumer.
     *
     * Idempotent for matching configs. Throws JetStreamApiException (code 10013)
     * if the existing consumer's immutable fields (deliverSubject, ackPolicy,
     * deliverPolicy, replayPolicy, …) don't match. Immutable-field changes
     * are deliberate operator decisions — resolve them with an explicit
     * `survey-worker --delete-consumer` run, then re-deploy. We do NOT
     * silently delete-then-recreate here: that would lose pending ack state,
     * which is safe for the idempotent anonymise use case but a copy-paste
     * foot-gun for any future non-idempotent consumer.
     */
    fun bootstrap(nats: Connection) {
        nats.jetStreamManagement().addOrUpdateConsumer(STREAM_NAME, consumerConfiguration())
    }

    /** Delete the durable consumer. Explicit operator action, used during
     *  planned migrations that change immutable consumer-config fields. */
    fun deleteConsumer(nats: Connection) {
        nats.jetStreamManagement().deleteConsumer(STREAM_NAME, DURABLE_NAME)
    }
}
