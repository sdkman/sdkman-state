package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.application.validation.CandidateRequestValidator
import io.sdkman.state.application.validation.ValidationError
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Version

fun VersionRequestValidator.checkVersionBody(body: String): Either<RejectedBody, Version> =
    if (allowListLoaded()) {
        validateRequest(body).mapLeft { it.toInvalidBody() }
    } else {
        RejectedBody.RegistryUnavailable.left()
    }

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
    }

private fun NonEmptyList<ValidationError>.toInvalidBody(): RejectedBody =
    RejectedBody.Invalid(map { ValidationFailure(it.field, it.message) })
