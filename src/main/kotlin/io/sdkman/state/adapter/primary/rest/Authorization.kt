package io.sdkman.state.adapter.primary.rest

import arrow.core.getOrElse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.Role

fun ApplicationCall.mayWriteCandidate(candidate: String): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role ->
            role == Role.ADMIN ||
                role == Role.COMMUNITY ||
                (role == Role.VENDOR && candidate in authenticatedCandidates())
        }.getOrElse { false }

fun ApplicationCall.mayManageCandidates(): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role -> role == Role.ADMIN || role == Role.COMMUNITY }
        .getOrElse { false }

fun ApplicationCall.mayManageVendors(): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role -> role == Role.ADMIN }
        .getOrElse { false }

suspend fun ApplicationCall.respondForbiddenFor(candidate: String): Unit =
    respond(HttpStatusCode.Forbidden, ErrorResponse("Forbidden", "Not authorized for candidate: $candidate"))
