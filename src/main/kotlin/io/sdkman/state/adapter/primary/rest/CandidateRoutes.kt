package io.sdkman.state.adapter.primary.rest

import arrow.core.raise.either
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.adapter.primary.rest.dto.toAdminDto
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.application.validation.CandidateRequestValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.service.CandidateService

private const val ADMIN_ROLE = "admin"

fun Route.candidateReadRoute(candidateService: CandidateService) {
    get("/candidates") {
        candidateService.list().fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { candidates ->
                call.respond(
                    HttpStatusCode.OK,
                    candidates.map { (candidate, default) -> candidate.toDto(default) },
                )
            },
        )
    }
}

fun Route.adminCreateCandidateRoute(candidateService: CandidateService) {
    post("/admin/candidates") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val role = call.authenticatedRole()
        if (role != ADMIN_ROLE) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@post
        }
        val requestBody = call.receiveText()
        CandidateRequestValidator.validateRequest(requestBody).fold(
            ifLeft = { errors ->
                val failures = errors.map { ValidationFailure(it.field, it.message) }
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation failed", failures))
            },
            ifRight = { registration ->
                candidateService.register(registration).fold(
                    ifLeft = { error -> call.respondDomainError(error) },
                    ifRight = { (candidate, created) ->
                        val status = if (created) HttpStatusCode.Created else HttpStatusCode.OK
                        call.respond(status, candidate.toAdminDto())
                    },
                )
            },
        )
    }
}

fun Route.adminDeleteCandidateRoute(candidateService: CandidateService) {
    delete("/admin/candidates/{candidate}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val role = call.authenticatedRole()
        if (role != ADMIN_ROLE) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@delete
        }
        either {
            val candidateId =
                call.parameters
                    .requiredPathParam("candidate")
                    .mapLeft { DomainError.ValidationFailed(it.message) }
                    .bind()
            candidateService.delete(candidateId).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { candidate -> call.respond(HttpStatusCode.OK, candidate.toAdminDto()) },
        )
    }
}
