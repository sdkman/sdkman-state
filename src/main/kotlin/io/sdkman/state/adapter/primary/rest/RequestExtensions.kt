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
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.config.JwtPrincipal
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditContext
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.NIL_UUID

fun ApplicationCall.jwtPrincipalOption(): Option<JwtPrincipal> = principal<JwtPrincipal>().toOption()

fun ApplicationCall.auditContext(): AuditContext =
    jwtPrincipalOption().fold(
        ifEmpty = { AuditContext(vendorId = NIL_UUID, email = "unknown") },
        ifSome = { AuditContext(vendorId = it.vendorId, email = it.email) },
    )

@Suppress("ReturnCount")
suspend fun ApplicationCall.requireAdmin(): Option<JwtPrincipal> {
    val principal = jwtPrincipalOption()
    if (principal.isNone() || principal.getOrElse { return None }.role != "admin") {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
        return None
    }
    return principal
}

@Suppress("ReturnCount")
suspend fun ApplicationCall.authorizeCandidate(candidate: String): Boolean {
    val principal =
        jwtPrincipalOption().getOrElse {
            respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return false
        }
    if (principal.role == "admin") return true
    if (candidate in principal.candidates) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse("Forbidden", "Not authorized for candidate '$candidate'"))
    return false
}

fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
    when (this.queryParameters["visible"].toOption()) {
        Some("all") -> None
        Some("false") -> Some(false)
        Some("true") -> Some(true)
        else -> Some(true)
    }

fun String.toDistribution(): Option<Distribution> = Distribution.entries.firstOrNone { it.name == this }

@Suppress("CyclomaticComplexMethod")
suspend fun ApplicationCall.respondDomainError(error: DomainError) {
    when (error) {
        is DomainError.Unauthorized ->
            respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", error.message))

        is DomainError.Forbidden ->
            respond(HttpStatusCode.Forbidden, ErrorResponse("Forbidden", error.message))

        is DomainError.VendorNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Vendor not found"))

        is DomainError.ValidationFailed ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", error.message))

        is DomainError.ValidationFailures ->
            respond(
                HttpStatusCode.BadRequest,
                ValidationErrorResponse(
                    "Validation Error",
                    error.failures.map { ValidationFailure(it.field, it.message) },
                ),
            )

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
