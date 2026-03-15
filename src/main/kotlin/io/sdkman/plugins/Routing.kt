@file:UseSerializers(OptionSerializer::class)

package io.sdkman.plugins

import arrow.core.*
import arrow.core.raise.either
import arrow.core.raise.option
import arrow.core.serialization.OptionSerializer
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.Distribution
import io.sdkman.domain.DomainError
import io.sdkman.domain.HealthRepository
import io.sdkman.domain.Platform
import io.sdkman.domain.TagService
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.VersionService
import io.sdkman.validation.UniqueTagValidator
import io.sdkman.validation.UniqueVersionValidator
import io.sdkman.validation.ValidationErrorResponse
import io.sdkman.validation.ValidationFailure
import io.sdkman.validation.VersionRequestValidator
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

@Serializable
data class TagConflictResponse(
    val error: String,
    val message: String,
    val tags: List<String>,
)

@Serializable
data class HealthCheckResponse(
    val status: String,
    val message: Option<String> = None,
)

private suspend fun ApplicationCall.respondDomainError(error: DomainError) {
    when (error) {
        is DomainError.ValidationFailed ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", error.message))

        is DomainError.ValidationFailures ->
            respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation Error", error.failures))

        is DomainError.VersionNotFound ->
            respond(HttpStatusCode.NotFound)

        is DomainError.TagNotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Tag '${error.tagName}' not found"))

        is DomainError.TagConflict ->
            respond(
                HttpStatusCode.Conflict,
                TagConflictResponse(
                    error = "Conflict",
                    message = "Cannot delete version with active tags. Remove or reassign the following tags first.",
                    tags = error.tags,
                ),
            )

        is DomainError.DatabaseError ->
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Database error", error.failure.message))
    }
}

private fun ApplicationCall.authenticatedUsername(): String =
    principal<UserIdPrincipal>()
        .toOption()
        .map { it.name }
        .getOrElse { "unknown" }

@Suppress("LongMethod") // Will be split in Phase 5.2 (VersionRoutes, TagRoutes, HealthRoutes)
fun Application.configureRouting(
    versionService: VersionService,
    tagService: TagService,
    healthRepo: HealthRepository,
) {
    fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
        when (this.queryParameters["visible"].toOption()) {
            Some("all") -> None
            Some("false") -> Some(false)
            Some("true") -> Some(true)
            else -> Some(true)
        }

    fun String.toDistribution(): Option<Distribution> = Distribution.entries.firstOrNone { it.name == this }

    routing {
        get("/meta/health") {
            healthRepo
                .checkDatabaseConnection()
                .map {
                    call.respond(
                        HttpStatusCode.OK,
                        HealthCheckResponse("SUCCESS"),
                    )
                }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthCheckResponse("FAILURE", failure.message.toOption()),
                    )
                }
        }
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
                val versions = versionService.findAll(candidateId, platform, distribution, visible)
                call.respond(HttpStatusCode.OK, versions)
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
                val maybeVersion = versionService.findOne(candidateId, versionId, platform, distribution)
                maybeVersion
                    .map { call.respond(HttpStatusCode.OK, it) }
                    .getOrElse { call.respond(HttpStatusCode.NotFound) }
            }.getOrElse { call.respond(HttpStatusCode.BadRequest) }
        }
        authenticate("auth-basic") {
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
                            .catch { call.receive<UniqueVersion>() }
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
            delete("/versions/tags") {
                val username = call.authenticatedUsername()
                either<DomainError, Unit> {
                    val uniqueTag =
                        Either
                            .catch { call.receive<UniqueTag>() }
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
    }
}
