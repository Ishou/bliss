package com.bliss.identity.api

import com.bliss.identity.api.config.IdentityApiConfig
import com.bliss.identity.application.ports.OidcProviderConfig
import com.bliss.identity.application.ports.OidcResponseMode
import com.bliss.identity.application.usecases.BeginOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteOidcLoginUseCase
import com.bliss.identity.application.usecases.CompleteProviderLinkUseCase
import com.bliss.identity.application.usecases.DeleteUserUseCase
import com.bliss.identity.application.usecases.GetMeUseCase
import com.bliss.identity.application.usecases.LogoutUseCase
import com.bliss.identity.application.usecases.UpdateMeUseCase
import com.bliss.identity.application.usecases.WhoAmIUseCase
import com.bliss.identity.domain.oidc.OidcVerifier
import com.bliss.identity.domain.provider.Provider
import com.bliss.identity.infrastructure.auth.SecureRandomFactory
import com.bliss.identity.infrastructure.events.InMemoryUserDeletedBroadcaster
import com.bliss.identity.infrastructure.id.UuidV7IdGenerator
import com.bliss.identity.infrastructure.oidc.JoseOidcVerifier
import com.bliss.identity.infrastructure.oidc.JwksCache
import com.bliss.identity.infrastructure.oidc.KtorOidcCodeExchanger
import com.bliss.identity.infrastructure.oidc.StaticOidcProviderConfigSource
import com.bliss.identity.infrastructure.persistence.PostgresAuthAttemptRepository
import com.bliss.identity.infrastructure.persistence.PostgresSessionRepository
import com.bliss.identity.infrastructure.persistence.PostgresUserProviderRepository
import com.bliss.identity.infrastructure.persistence.PostgresUserRepository
import com.bliss.identity.infrastructure.time.SystemClock
import io.ktor.client.engine.HttpClientEngine
import java.time.Duration
import javax.sql.DataSource

// Hand-rolled DI graph for identity-api. Constructs every adapter + use case from
// the runtime config + Postgres `DataSource`. Tests use `forTesting(...)` to supply
// only the use cases the route under test exercises.
class Wiring private constructor(
    private val _beginOidcLogin: BeginOidcLoginUseCase?,
    private val _completeOidcLogin: CompleteOidcLoginUseCase?,
    private val _completeProviderLink: CompleteProviderLinkUseCase?,
    private val _whoAmI: WhoAmIUseCase?,
    private val _logout: LogoutUseCase?,
    private val _getMe: GetMeUseCase?,
    private val _updateMe: UpdateMeUseCase?,
    private val _deleteUser: DeleteUserUseCase?,
) {
    val beginOidcLogin: BeginOidcLoginUseCase get() = require(_beginOidcLogin, "BeginOidcLoginUseCase")
    val completeOidcLogin: CompleteOidcLoginUseCase get() = require(_completeOidcLogin, "CompleteOidcLoginUseCase")
    val completeProviderLink: CompleteProviderLinkUseCase get() = require(_completeProviderLink, "CompleteProviderLinkUseCase")
    val whoAmI: WhoAmIUseCase get() = require(_whoAmI, "WhoAmIUseCase")
    val logout: LogoutUseCase get() = require(_logout, "LogoutUseCase")
    val getMe: GetMeUseCase get() = require(_getMe, "GetMeUseCase")
    val updateMe: UpdateMeUseCase get() = require(_updateMe, "UpdateMeUseCase")
    val deleteUser: DeleteUserUseCase get() = require(_deleteUser, "DeleteUserUseCase")

    // Nullable peek accessors so Module.kt can mount only the routes whose use case is wired,
    // letting tests supply a slim Wiring.forTesting(...) for the route under test.
    internal val beginOidcLoginOrNull: BeginOidcLoginUseCase? get() = _beginOidcLogin
    internal val completeOidcLoginOrNull: CompleteOidcLoginUseCase? get() = _completeOidcLogin
    internal val whoAmIOrNull: WhoAmIUseCase? get() = _whoAmI

    private fun <T : Any> require(
        value: T?,
        name: String,
    ): T = value ?: error("Test wiring did not provide $name; the route under test must not call it.")

    companion object {
        fun forProduction(
            config: IdentityApiConfig,
            dataSource: DataSource,
            httpClientEngine: HttpClientEngine,
        ): Wiring {
            val clock = SystemClock
            val idGen = UuidV7IdGenerator()
            val random = SecureRandomFactory()

            val users = PostgresUserRepository(dataSource)
            val userProviders = PostgresUserProviderRepository(dataSource)
            val sessions = PostgresSessionRepository(dataSource)
            val attempts = PostgresAuthAttemptRepository(dataSource)

            val providerConfigs =
                mapOf(
                    Provider.GOOGLE to
                        OidcProviderConfig(
                            provider = Provider.GOOGLE,
                            issuer = "https://accounts.google.com",
                            audience = config.google.clientId,
                            clientId = config.google.clientId,
                            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
                            tokenUrl = "https://oauth2.googleapis.com/token",
                            jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
                            redirectUri = "https://${config.publicHost}/v1/auth/google/callback",
                            responseMode = OidcResponseMode.QUERY,
                            clientAuth = config.googleAuth,
                        ),
                    Provider.APPLE to
                        OidcProviderConfig(
                            provider = Provider.APPLE,
                            issuer = "https://appleid.apple.com",
                            audience = config.apple.serviceId,
                            clientId = config.apple.serviceId,
                            authorizeUrl = "https://appleid.apple.com/auth/authorize",
                            tokenUrl = "https://appleid.apple.com/auth/token",
                            jwksUri = "https://appleid.apple.com/auth/keys",
                            redirectUri = "https://${config.publicHost}/v1/auth/apple/callback",
                            responseMode = OidcResponseMode.FORM_POST,
                            clientAuth = config.appleAuth,
                        ),
                )

            val configSource = StaticOidcProviderConfigSource(providerConfigs)

            val jwksCache =
                JwksCache.defaultProduction(
                    ttl = Duration.ofMinutes(5),
                    clock = { clock.now() },
                )
            val verifier: OidcVerifier = JoseOidcVerifier(jwksCache, clock = { clock.now() })

            val codeExchanger =
                KtorOidcCodeExchanger(
                    configSource = configSource,
                    engine = httpClientEngine,
                    clock = clock,
                )

            // TODO(phase-6): replace with HttpUserDeletedBroadcaster fanning out to grid + game.
            val broadcaster = InMemoryUserDeletedBroadcaster()

            return Wiring(
                _beginOidcLogin =
                    BeginOidcLoginUseCase(
                        configSource,
                        random,
                        idGen,
                        attempts,
                        clock,
                        config.attemptTtl,
                    ),
                _completeOidcLogin =
                    CompleteOidcLoginUseCase(
                        attempts,
                        codeExchanger,
                        verifier,
                        configSource,
                        users,
                        userProviders,
                        sessions,
                        idGen,
                        clock,
                    ),
                _completeProviderLink =
                    CompleteProviderLinkUseCase(
                        attempts,
                        codeExchanger,
                        verifier,
                        configSource,
                        userProviders,
                        clock,
                    ),
                _whoAmI = WhoAmIUseCase(users, sessions, clock, config.sessionMaxAge),
                _logout = LogoutUseCase(sessions, clock),
                _getMe = GetMeUseCase(users, userProviders),
                _updateMe = UpdateMeUseCase(users),
                _deleteUser = DeleteUserUseCase(users, broadcaster, clock),
            )
        }

        fun forTesting(
            beginOidcLogin: BeginOidcLoginUseCase? = null,
            completeOidcLogin: CompleteOidcLoginUseCase? = null,
            completeProviderLink: CompleteProviderLinkUseCase? = null,
            whoAmI: WhoAmIUseCase? = null,
            logout: LogoutUseCase? = null,
            getMe: GetMeUseCase? = null,
            updateMe: UpdateMeUseCase? = null,
            deleteUser: DeleteUserUseCase? = null,
        ): Wiring =
            Wiring(
                _beginOidcLogin = beginOidcLogin,
                _completeOidcLogin = completeOidcLogin,
                _completeProviderLink = completeProviderLink,
                _whoAmI = whoAmI,
                _logout = logout,
                _getMe = getMe,
                _updateMe = updateMe,
                _deleteUser = deleteUser,
            )
    }
}
