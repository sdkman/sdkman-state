package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.none
import arrow.core.right
import arrow.core.some
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.TagConflictResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform

fun ApplicationCall.authenticatedVendorId(): java.util.UUID =
    principal<JWTPrincipal>()
        .toOption()
        .flatMap {
            runCatching {
                java.util.UUID.fromString(it.payload.getClaim("vendor_id").asString())
            }.getOrNull().toOption()
        }.getOrElse { java.util.UUID(0L, 0L) }

fun ApplicationCall.authenticatedEmail(): String =
    principal<JWTPrincipal>()
        .toOption()
        .map { it.payload.subject }
        .getOrElse { "unknown" }

fun ApplicationCall.authenticatedRole(): String =
    principal<JWTPrincipal>()
        .toOption()
        .map { it.payload.getClaim("role").asString() }
        .getOrElse { "unknown" }

fun ApplicationCall.authenticatedCandidates(): List<String> =
    principal<JWTPrincipal>()
        .toOption()
        .flatMap {
            it.payload
                .getClaim("candidates")
                .asList(String::class.java)
                .toOption()
        }.getOrElse { emptyList() }

fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
    when (this.queryParameters["visible"].toOption()) {
        Some("all") -> none()
        Some("false") -> Some(false)
        Some("true") -> Some(true)
        else -> Some(true)
    }

fun String.toDistribution(): Option<Distribution> = Distribution.entries.firstOrNone { it.name == this }

private val platformVocabulary: String = Platform.entries.joinToString(", ") { it.name }
private val distributionVocabulary: String = Distribution.entries.joinToString(", ") { it.name }
private const val VISIBLE_VOCABULARY: String = "true, false, all"

fun ApplicationRequest.platformQueryParam(): Either<ErrorResponse, Option<Platform>> =
    queryParameters["platform"].toOption().fold(
        { none<Platform>().right() },
        { value ->
            Platform.entries
                .firstOrNone { it.name == value }
                .toEither { invalidPlatformError(value) }
                .map { it.some() }
        },
    )

fun ApplicationRequest.requiredPlatformQueryParam(): Either<ErrorResponse, Platform> =
    queryParameters["platform"].toOption().fold(
        { missingPlatformError().left() },
        { value ->
            Platform.entries
                .firstOrNone { it.name == value }
                .toEither { invalidPlatformError(value) }
        },
    )

fun ApplicationRequest.distributionQueryParam(): Either<ErrorResponse, Option<Distribution>> =
    queryParameters["distribution"].toOption().fold(
        { none<Distribution>().right() },
        { value ->
            Distribution.entries
                .firstOrNone { it.name == value }
                .toEither { invalidDistributionError(value) }
                .map { it.some() }
        },
    )

fun ApplicationRequest.strictVisibleQueryParam(): Either<ErrorResponse, Option<Boolean>> =
    queryParameters["visible"].toOption().fold(
        { true.some().right() },
        { value ->
            when (value) {
                "true" -> true.some().right()
                "false" -> false.some().right()
                "all" -> none<Boolean>().right()
                else -> invalidVisibleError(value).left()
            }
        },
    )

private fun invalidPlatformError(value: String): ErrorResponse =
    ErrorResponse(
        "Bad Request",
        "Invalid platform '$value'. Expected one of: $platformVocabulary.",
    )

private fun missingPlatformError(): ErrorResponse =
    ErrorResponse(
        "Bad Request",
        "Missing required parameter: platform. Expected one of: $platformVocabulary.",
    )

private fun invalidDistributionError(value: String): ErrorResponse =
    ErrorResponse(
        "Bad Request",
        "Invalid distribution '$value'. Expected one of: $distributionVocabulary.",
    )

private fun invalidVisibleError(value: String): ErrorResponse =
    ErrorResponse(
        "Bad Request",
        "Invalid visible '$value'. Expected one of: $VISIBLE_VOCABULARY.",
    )

suspend fun ApplicationCall.respondDomainError(error: DomainError) {
    when (error) {
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
