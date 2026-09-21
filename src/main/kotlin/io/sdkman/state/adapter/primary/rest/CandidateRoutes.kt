package io.sdkman.state.adapter.primary.rest

import arrow.core.raise.either
import io.ktor.http.*
import io.ktor.server.application.*
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
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.CandidateRegistrationResult
import io.sdkman.state.domain.service.CandidateAllowList
import io.sdkman.state.domain.service.CandidateService

private const val ADMIN_ROLE = "admin"

fun Route.candidateReadRoute(candidateService: CandidateService) {
    get("/candidates") {
        candidateService.list().fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { candidates -> call.respond(HttpStatusCode.OK, candidates.map { it.toDto() }) },
        )
    }
}

fun Route.adminCreateCandidateRoute(
    candidateService: CandidateService,
    candidateAllowList: CandidateAllowList,
) {
    post("/admin/candidates") {
        call.declineCaching()
        if (!call.isAuthenticatedAdmin()) {
            call.respondUnauthorized()
            return@post
        }
        CandidateRequestValidator.validateRequest(call.receiveText()).fold(
            ifLeft = { errors -> call.respondValidationFailure(errors.map { ValidationFailure(it.field, it.message) }) },
            ifRight = { registration -> call.respondRegistration(candidateService, candidateAllowList, registration) },
        )
    }
}

fun Route.adminDeleteCandidateRoute(
    candidateService: CandidateService,
    candidateAllowList: CandidateAllowList,
) {
    delete("/admin/candidates/{candidate}") {
        call.declineCaching()
        if (!call.isAuthenticatedAdmin()) {
            call.respondUnauthorized()
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
            ifRight = { candidate ->
                candidateAllowList.refresh()
                call.respond(HttpStatusCode.OK, candidate.toAdminDto())
            },
        )
    }
}

private suspend fun ApplicationCall.respondRegistration(
    candidateService: CandidateService,
    candidateAllowList: CandidateAllowList,
    registration: CandidateRegistration,
) = candidateService.register(registration).fold(
    ifLeft = { error -> respondDomainError(error) },
    ifRight = { result ->
        candidateAllowList.refresh()
        respond(result.status(), result.candidate.toAdminDto())
    },
)

private suspend fun ApplicationCall.respondValidationFailure(failures: List<ValidationFailure>) =
    respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation failed", failures))

private suspend fun ApplicationCall.respondUnauthorized() =
    respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))

private fun ApplicationCall.isAuthenticatedAdmin(): Boolean = authenticatedRole() == ADMIN_ROLE

private fun ApplicationCall.declineCaching() = response.header(HttpHeaders.CacheControl, "no-store")

private fun CandidateRegistrationResult.status(): HttpStatusCode =
    when (this) {
        is CandidateRegistrationResult.Registered -> HttpStatusCode.Created
        is CandidateRegistrationResult.Updated -> HttpStatusCode.OK
    }
