package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.application.validation.CandidateRequestValidator
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Version

fun versionValidationAnswer(
    validator: VersionRequestValidator,
    body: String,
): Either<Rejection, Version> =
    if (!validator.allowListLoaded()) {
        Either.Left(Rejection.RegistryUnavailable)
    } else {
        validator.validateRequest(body).mapLeft { errors ->
            Rejection.Invalid(
                ValidationErrorResponse("Validation failed", errors.map { ValidationFailure(it.field, it.message) }),
            )
        }
    }

fun candidateValidationAnswer(body: String): Either<Rejection, CandidateRegistration> =
    CandidateRequestValidator.validateRequest(body).mapLeft { errors ->
        Rejection.Invalid(
            ValidationErrorResponse("Validation failed", errors.map { ValidationFailure(it.field, it.message) }),
        )
    }

suspend fun ApplicationCall.respondRejection(rejection: Rejection) =
    when (rejection) {
        is Rejection.RegistryUnavailable -> respond(HttpStatusCode.InternalServerError, rejection.body)
        is Rejection.Invalid -> respond(HttpStatusCode.BadRequest, rejection.body)
    }

sealed interface Rejection {
    data object RegistryUnavailable : Rejection {
        val body = ErrorResponse("Internal Server Error", "Candidate registry unavailable")
    }

    data class Invalid(
        val body: ValidationErrorResponse,
    ) : Rejection
}
