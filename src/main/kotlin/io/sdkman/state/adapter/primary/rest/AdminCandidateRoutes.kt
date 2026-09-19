package io.sdkman.state.adapter.primary.rest

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.CandidateConflictResponse
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.adapter.primary.rest.dto.toAdminDto
import io.sdkman.state.application.validation.CandidateRequestValidator
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.service.CandidateService

// Registration is an upsert keyed on the identifier: an existing candidate has its
// metadata refreshed. `201` and `200` are told apart by what the write itself reports,
// never by a preceding read, so a concurrent double-post cannot answer `201` twice.
// A valid non-admin token answers `401` rather than `403`, matching the vendor admin
// routes it sits beside.
fun Route.adminCreateCandidateRoute(candidateService: CandidateService) {
    post("/admin/candidates") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val role = call.authenticatedRole()
        if (role != "admin") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@post
        }
        // The body is read as text and handed to the validator rather than deserialised
        // into a typed parameter. A typed body throws before the handler can shape a
        // response, which turns malformed JSON into a `500`; the spec requires a `400`
        // like any other validation failure. `POST /versions` reads its body the same way.
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

// Deletion is hard, not soft: unlike a vendor, a candidate carries no history worth
// keeping once its versions are gone. It is refused while any version still names the
// candidate, so rows are never orphaned, and the refusal carries the count in a
// structured body rather than in prose. Only versions are counted — a `version_tags`
// row cannot outlive its version.
fun Route.adminDeleteCandidateRoute(candidateService: CandidateService) {
    delete("/admin/candidates/{candidate}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val role = call.authenticatedRole()
        if (role != "admin") {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized", "Invalid or expired token"))
            return@delete
        }
        call.parameters.requiredPathParam("candidate").fold(
            ifLeft = { error -> call.respond(HttpStatusCode.BadRequest, error) },
            ifRight = { candidateId ->
                candidateService.delete(candidateId).fold(
                    ifLeft = { error -> call.respondDomainError(error) },
                    ifRight = { deletion -> call.respondCandidateDeletion(deletion) },
                )
            },
        )
    }
}

private suspend fun ApplicationCall.respondCandidateDeletion(deletion: CandidateDeletion) {
    when (deletion) {
        is CandidateDeletion.Deleted ->
            respond(HttpStatusCode.OK, deletion.candidate.toAdminDto())

        is CandidateDeletion.NotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Candidate not found"))

        is CandidateDeletion.HasVersions ->
            respond(
                HttpStatusCode.Conflict,
                CandidateConflictResponse(
                    error = "Conflict",
                    message = "Cannot delete candidate with existing versions. Remove the versions first.",
                    versionCount = deletion.versionCount,
                ),
            )
    }
}
