package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.UniqueTagDto
import io.sdkman.state.adapter.primary.rest.dto.toDomain
import io.sdkman.state.application.validation.UniqueTagValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.error.FieldError
import io.sdkman.state.domain.service.TagService

fun Route.tagRoutes(tagService: TagService) {
    delete("/versions/tags") {
        val auditContext = call.auditContext()
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
            if (!call.authorizeCandidate(uniqueTag.candidate)) return@delete
            tagService.deleteTag(uniqueTag, auditContext).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
