package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.UniqueTagDto
import io.sdkman.state.adapter.primary.rest.dto.toDomain
import io.sdkman.state.application.validation.UniqueTagValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.error.FieldError
import io.sdkman.state.domain.service.TagService

fun Route.tagRoutes(tagService: TagService) {
    delete("/versions/tags") {
        val vendorId = call.authenticatedVendorId()
        val email = call.authenticatedEmail()
        val role = call.authenticatedRole()
        val candidates = call.authenticatedCandidates()
        either<DomainError, Unit> {
            val uniqueTag =
                Either
                    .catch { call.receive<UniqueTagDto>() }
                    .map { it.toDomain() }
                    .mapLeft {
                        DomainError.ValidationFailed(
                            "Invalid request: ${it.message.toOption().getOrElse { "Unknown error" }}",
                        )
                    }.bind()
            UniqueTagValidator
                .validate(uniqueTag)
                .mapLeft { errors ->
                    DomainError.ValidationFailures(errors.map { FieldError(it.field, it.message) })
                }.bind()
            // Admin tokens bypass candidate authorization — admin can operate on any candidate
            if (role == "vendor" && uniqueTag.candidate !in candidates) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Forbidden", "Not authorized for candidate: ${uniqueTag.candidate}"),
                )
                return@delete
            }
            tagService.deleteTag(uniqueTag, vendorId, email).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
