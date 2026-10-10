package io.sdkman.state.adapter.primary.rest

import arrow.core.getOrElse
import io.ktor.server.application.*
import io.sdkman.state.domain.model.Role

fun ApplicationCall.mayWriteCandidate(candidate: String): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role ->
            role == Role.ADMIN ||
                role == Role.COMMUNITY ||
                (role == Role.VENDOR && candidate in authenticatedCandidates())
        }.getOrElse { false }
