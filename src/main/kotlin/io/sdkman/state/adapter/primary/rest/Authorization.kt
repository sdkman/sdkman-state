package io.sdkman.state.adapter.primary.rest

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.some
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.application.validation.JavaPoster
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Role

fun ApplicationCall.mayWriteCandidate(candidate: String): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role ->
            role == Role.ADMIN ||
                role == Role.COMMUNITY ||
                (role == Role.VENDOR && candidate in authenticatedCandidates())
        }.getOrElse { false }

fun ApplicationCall.mayWriteTag(candidate: String): Boolean = if (candidate == JAVA_CANDIDATE) isAdmin() else mayWriteCandidate(candidate)

fun ApplicationCall.javaPoster(): Option<JavaPoster> =
    Role
        .fromClaim(authenticatedRole())
        .flatMap { role ->
            when {
                role == Role.ADMIN -> JavaPoster.ADMIN.some()
                role == Role.VENDOR && JAVA_CANDIDATE in authenticatedCandidates() -> JavaPoster.VENDOR.some()
                else -> none()
            }
        }

fun ApplicationCall.mayManageCandidates(): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role -> role == Role.ADMIN || role == Role.COMMUNITY }
        .getOrElse { false }

fun ApplicationCall.mayManageVendors(): Boolean = isAdmin()

private fun ApplicationCall.isAdmin(): Boolean =
    Role
        .fromClaim(authenticatedRole())
        .map { role -> role == Role.ADMIN }
        .getOrElse { false }

suspend fun ApplicationCall.respondForbiddenFor(candidate: String): Unit =
    respond(HttpStatusCode.Forbidden, ErrorResponse("Forbidden", "Not authorized for candidate: $candidate"))
