package com.bliss.identity.domain.provider

import com.bliss.identity.domain.user.UserId
import java.time.Instant

data class UserProvider(
    val userId: UserId,
    val provider: Provider,
    val subject: Subject,
    val emailAtLink: String?, // only when user opted in; see ADR-0045
    val linkedAt: Instant,
)
