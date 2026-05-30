package com.bliss.identity.api

import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.application.usecases.SetUserRoleOutcome
import com.bliss.identity.application.usecases.SetUserRoleUseCase
import com.bliss.identity.domain.user.Role
import com.bliss.identity.domain.user.UserId
import com.bliss.identity.infrastructure.events.NatsConnectionFactory
import com.bliss.identity.infrastructure.events.NatsUserRoleChangedBroadcaster
import com.bliss.identity.infrastructure.persistence.IdentityDatabase
import com.bliss.identity.infrastructure.persistence.PostgresUserRepository
import com.bliss.identity.infrastructure.time.SystemClock
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("identity-api")

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--set-maintainer-roles") {
        exitProcess(runSetMaintainerRoles())
    }

    val config = IdentityApiConfig.fromEnv()
    val port = System.getenv("PORT")?.toIntOrNull() ?: config.port
    val db =
        IdentityDatabase(
            poolName = "identity-api",
            maxPoolSize = 10,
            requireUrl = true,
        ).apply { start() }
    val dataSource = db.dataSource() ?: error("IdentityDatabase did not produce a DataSource.")
    val natsUrl = System.getenv("NATS_URL") ?: error("NATS_URL env var is required")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(config, dataSource, CIO.create(), natsUrl)
    }.start(wait = true)
}

internal suspend fun setMaintainerRoles(
    rawIds: String?,
    useCase: SetUserRoleUseCase,
): List<SetUserRoleOutcome> =
    (rawIds ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { useCase.execute(UserId.parse(it), Role.MAINTAINER) }

private fun runSetMaintainerRoles(): Int {
    val db =
        IdentityDatabase(poolName = "identity-role-bootstrap", maxPoolSize = 2, requireUrl = true)
            .apply { start() }
    val dataSource = db.dataSource() ?: error("IdentityDatabase did not produce a DataSource.")
    val natsUrl = System.getenv("NATS_URL") ?: error("NATS_URL env var is required")
    val (connection, jetStream) = NatsConnectionFactory(natsUrl).connect()
    return try {
        val useCase =
            SetUserRoleUseCase(
                users = PostgresUserRepository(dataSource),
                broadcaster = NatsUserRoleChangedBroadcaster(jetStream),
                clock = SystemClock,
            )
        val outcomes = runBlocking { setMaintainerRoles(System.getenv("MAINTAINER_USER_IDS"), useCase) }
        outcomes.forEach { outcome ->
            when (outcome) {
                is SetUserRoleOutcome.Changed ->
                    log.info("event=role_set user={} role={}", outcome.userId, outcome.role.wire)
                is SetUserRoleOutcome.Unchanged ->
                    log.info("event=role_unchanged user={} role={}", outcome.userId, outcome.role.wire)
                is SetUserRoleOutcome.UserNotFound ->
                    log.warn("event=role_user_not_found user={}", outcome.userId)
            }
        }
        0
    } catch (e: Throwable) {
        log.error("event=role_bootstrap_failed reason={}", e.message, e)
        1
    } finally {
        connection.close()
    }
}
