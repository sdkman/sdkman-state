package io.sdkman.state.adapter.primary.rest

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.option
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.UniqueVersionDto
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.adapter.primary.rest.dto.toDomain
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.application.validation.UniqueVersionValidator
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.service.VersionService

fun Route.versionReadRoutes(versionService: VersionService) {
    get("/versions/{candidate}") {
        option {
            val candidateId =
                call.parameters["candidate"]
                    .toOption()
                    .filter { it.isNotBlank() }
                    .bind()
            val visible = call.request.visibleQueryParam()
            val platform =
                call.request.queryParameters["platform"]
                    .toOption()
                    .map { Platform.findByPlatformId(it) }
            val distribution =
                call.request.queryParameters["distribution"]
                    .toOption()
                    .flatMap { it.toDistribution() }
            versionService.findByCandidate(candidateId, platform, distribution, visible).fold(
                ifLeft = { error -> call.respondDomainError(error) },
                ifRight = { versions -> call.respond(HttpStatusCode.OK, versions.map { it.toDto() }) },
            )
        }.getOrElse {
            call.respond(HttpStatusCode.BadRequest)
        }
    }
    get("/versions/{candidate}/{version}") {
        option {
            val candidateId =
                call.parameters["candidate"]
                    .toOption()
                    .filter { it.isNotBlank() }
                    .bind()
            val versionId =
                call.parameters["version"]
                    .toOption()
                    .filter { it.isNotBlank() }
                    .bind()
            val platform =
                call.request.queryParameters["platform"]
                    .toOption()
                    .map { Platform.findByPlatformId(it) }
                    .getOrElse { Platform.UNIVERSAL }
            val distribution =
                call.request.queryParameters["distribution"]
                    .toOption()
                    .flatMap { it.toDistribution() }
            versionService.findUnique(candidateId, versionId, platform, distribution).fold(
                ifLeft = { error -> call.respondDomainError(error) },
                ifRight = { maybeVersion ->
                    maybeVersion
                        .map { call.respond(HttpStatusCode.OK, it.toDto()) }
                        .getOrElse { call.respond(HttpStatusCode.NotFound) }
                },
            )
        }.getOrElse { call.respond(HttpStatusCode.BadRequest) }
    }
}

fun Route.versionWriteRoutes(versionService: VersionService) {
    post("/versions") {
        val username = call.authenticatedUsername()
        val requestBody = call.receiveText()
        VersionRequestValidator.validateRequest(requestBody).fold(
            ifLeft = { errors ->
                val failures = errors.map { ValidationFailure(it.field, it.message) }
                call.respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation failed", failures))
            },
            ifRight = { validVersion ->
                versionService.createOrUpdate(validVersion, username).fold(
                    ifLeft = { error -> call.respondDomainError(error) },
                    ifRight = { call.respond(HttpStatusCode.NoContent) },
                )
            },
        )
    }
    delete("/versions") {
        val username = call.authenticatedUsername()
        either<DomainError, Unit> {
            val uniqueVersion =
                Either
                    .catch { call.receive<UniqueVersionDto>() }
                    .map { it.toDomain() }
                    .mapLeft {
                        DomainError.ValidationFailed(
                            "Invalid request: ${it.message.toOption().getOrElse { "Unknown error" }}",
                        )
                    }.bind()
            val validUniqueVersion =
                UniqueVersionValidator
                    .validate(uniqueVersion)
                    .mapLeft { DomainError.ValidationFailed(it.message) }
                    .bind()
            versionService.delete(validUniqueVersion, username).bind()
        }.fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}
