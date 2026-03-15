package io.sdkman.plugins

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.DomainError
import io.sdkman.domain.TagService
import io.sdkman.dto.UniqueTagDto
import io.sdkman.dto.toDomain
import io.sdkman.validation.UniqueTagValidator
import io.sdkman.validation.ValidationFailure

fun Route.tagRoutes(tagService: TagService) {
    delete("/versions/tags") {
        val username = call.authenticatedUsername()
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
                    DomainError.ValidationFailures(errors.map { ValidationFailure(it.field, it.message) })
                }.bind()
            tagService.deleteTag(uniqueTag, username).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
