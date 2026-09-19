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

// `authenticatedRole` answers the claim verbatim, so a vendor token reaches the handler and is
// refused here rather than at the authentication layer.
private const val ADMIN_ROLE = "admin"

/**
 * Sets no `Cache-Control` of its own *on purpose*: that absence is what lets the `CachingHeaders`
 * plugin installed by [versionReadRoutes] apply the same `max-age` the version read routes carry.
 *
 * The service result is mapped as it arrives and never re-sorted — the order is contractual.
 */
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

/**
 * The body is read as text and decoded inside [CandidateRequestValidator] so malformed JSON is an
 * accumulated `400`. Deserialising straight into the request type — as the neighbouring vendor
 * admin routes do — throws before the handler can shape a response and surfaces as a `500`.
 *
 * `201` and `200` are told apart by what the upsert reports, never by a preceding read, so a
 * concurrent double post of a new candidate cannot answer `201` twice.
 */
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

/**
 * No database constraint stands behind the `409` — the service's version count is the whole guard
 * — so this route must never bypass the service and delete directly.
 */
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
