package io.sdkman.state.adapter.primary.rest

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.TagConflictResponse
import io.sdkman.state.application.validation.ValidationErrorResponse
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution

fun ApplicationCall.authenticatedUsername(): String =
    principal<UserIdPrincipal>()
        .toOption()
        .map { it.name }
        .getOrElse { "unknown" }

fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
    when (this.queryParameters["visible"].toOption()) {
        Some("all") -> None
        Some("false") -> Some(false)
        Some("true") -> Some(true)
        else -> Some(true)
    }

fun String.toDistribution(): Option<Distribution> = Distribution.entries.firstOrNone { it.name == this }

suspend fun ApplicationCall.respondDomainError(error: DomainError) {
    when (error) {
        is DomainError.ValidationFailed ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", error.message))

        is DomainError.ValidationFailures ->
            respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation Error", error.failures))

        is DomainError.VersionNotFound ->
            respond(HttpStatusCode.NotFound)

        is DomainError.TagNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Tag '${error.tagName}' not found"))

        is DomainError.TagConflict ->
            respond(
                HttpStatusCode.Conflict,
                TagConflictResponse(
                    error = "Conflict",
                    message = "Cannot delete version with active tags. Remove or reassign the following tags first.",
                    tags = error.tags,
                ),
            )

        is DomainError.DatabaseError ->
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Database error", error.failure.message))
    }
}
