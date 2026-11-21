package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.application.validation.CandidateRequestValidator
import io.sdkman.state.application.validation.JavaPoster
import io.sdkman.state.application.validation.JavaVersionRequestValidator
import io.sdkman.state.application.validation.ValidationError
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Version

suspend fun VersionRequestValidator.checkVersionBody(body: String): Either<RejectedBody, Version> =
    checkUnlessRegistryUnavailable(allowListLoaded()) { validateRequest(body) }
        .flatMap { version -> checkPlatformShape(version) }

fun JavaVersionRequestValidator.checkJavaVersionBody(
    body: String,
    poster: JavaPoster,
): Either<RejectedBody, Version> = checkUnlessRegistryUnavailable(allowListLoaded()) { validateRequest(body, poster) }

fun checkCandidateBody(body: String): Either<RejectedBody, CandidateRegistration> =
    CandidateRequestValidator.validateRequest(body).mapLeft { it.toInvalidBody() }

suspend fun ApplicationCall.respondRejectedBody(rejection: RejectedBody) =
    when (rejection) {
        RejectedBody.RegistryUnavailable ->
            respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Internal Server Error", "Candidate registry unavailable"),
            )
        is RejectedBody.Invalid ->
            respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation failed", rejection.failures))
        is RejectedBody.DatabaseUnavailable ->
            respondDomainError(DomainError.DatabaseError(rejection.failure))
    }

private suspend fun VersionRequestValidator.checkPlatformShape(version: Version): Either<RejectedBody, Version> =
    validatePlatformShape(version).fold(
        ifLeft = { failure -> RejectedBody.DatabaseUnavailable(failure).left() },
        ifRight = { errors ->
            errors.toNonEmptyListOrNone().fold(
                ifEmpty = { version.right() },
                ifSome = { shapeErrors -> shapeErrors.toInvalidBody().left() },
            )
        },
    )

private fun checkUnlessRegistryUnavailable(
    registryLoaded: Boolean,
    validate: () -> Either<NonEmptyList<ValidationError>, Version>,
): Either<RejectedBody, Version> =
    if (registryLoaded) {
        validate().mapLeft { it.toInvalidBody() }
    } else {
        RejectedBody.RegistryUnavailable.left()
    }

private fun NonEmptyList<ValidationError>.toInvalidBody(): RejectedBody =
    RejectedBody.Invalid(map { ValidationFailure(it.field, it.message) })
