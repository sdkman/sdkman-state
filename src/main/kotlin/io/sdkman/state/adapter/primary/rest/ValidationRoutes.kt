package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.application.validation.VersionRequestValidator

fun Route.validateVersionRoute(versionRequestValidator: VersionRequestValidator) {
    post("/validate/versions") {
        call.respondValidation { body -> versionRequestValidator.checkVersionBody(body) }
    }
}

fun Route.validateCandidateRoute() {
    post("/validate/candidates") {
        call.respondValidation { body -> checkCandidateBody(body) }
    }
}

private suspend fun ApplicationCall.respondValidation(check: (String) -> Either<RejectedBody, Any>) {
    response.header(HttpHeaders.CacheControl, "no-store")
    receiveTextWithin(VALIDATION_BODY_LIMIT_BYTES).fold(
        ifEmpty = { respond(HttpStatusCode.PayloadTooLarge) },
        ifSome = { body ->
            check(body).fold(
                ifLeft = { rejection -> respondRejectedBody(rejection) },
                ifRight = { respond(HttpStatusCode.NoContent) },
            )
        },
    )
}
