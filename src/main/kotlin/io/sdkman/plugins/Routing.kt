package io.sdkman.plugins

import arrow.core.*
import arrow.core.raise.either
import arrow.core.raise.option
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.AuditRepository
import io.sdkman.domain.Distribution
import io.sdkman.domain.HealthRepository
import io.sdkman.domain.HealthStatus
import io.sdkman.domain.Platform
import io.sdkman.domain.TagsRepository
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.repos.VersionsRepository
import io.sdkman.validation.UniqueTagValidator
import io.sdkman.validation.UniqueVersionValidator
import io.sdkman.validation.ValidationErrorResponse
import io.sdkman.validation.ValidationFailure
import io.sdkman.validation.VersionRequestValidator
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    val status: HealthStatus,
    val message: String? = null,
)

private sealed interface DeleteError {
    data class Validation(
        val message: String,
    ) : DeleteError

    data object NotFound : DeleteError

    data class TagConflict(
        val tags: List<String>,
    ) : DeleteError

    data class Database(
        val message: String,
    ) : DeleteError
}

private sealed interface DeleteTagError {
    data class Deserialization(
        val message: String,
    ) : DeleteTagError

    data class Validation(
        val failures: List<ValidationFailure>,
    ) : DeleteTagError

    data class NotFound(
        val tagName: String,
    ) : DeleteTagError

    data class Database(
        val message: String,
    ) : DeleteTagError
}

private suspend fun ApplicationCall.respondDeleteTagError(error: DeleteTagError) {
    when (error) {
        is DeleteTagError.Deserialization ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("Bad Request", error.message))

        is DeleteTagError.Validation ->
            respond(HttpStatusCode.BadRequest, ValidationErrorResponse("Validation Error", error.failures))

        is DeleteTagError.NotFound ->
            respond(HttpStatusCode.NotFound, ErrorResponse("Not Found", "Tag '${error.tagName}' not found"))

        is DeleteTagError.Database ->
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Database Error", error.message))
    }
}

private fun ApplicationCall.authenticatedUsername(): String =
    principal<UserIdPrincipal>()
        .toOption()
        .map { it.name }
        .getOrElse { "unknown" }

private suspend fun logAudit(
    logger: Logger,
    auditRepo: AuditRepository,
    username: String,
    operation: AuditOperation,
    version: Version,
) {
    auditRepo.recordAudit(username, operation, version).onLeft { error ->
        logger.warn("Audit logging failed: ${error.message}", error)
    }
}

private suspend fun processTags(
    logger: Logger,
    tagsRepo: TagsRepository,
    versionId: Int,
    version: Version,
) {
    version.tags.onSome { tagList ->
        tagsRepo
            .replaceTags(
                versionId = versionId,
                candidate = version.candidate,
                distribution = version.distribution,
                platform = version.platform,
                tags = tagList,
            ).onLeft { error ->
                logger.warn("Tag processing failed: ${error.message}", error)
            }
    }
}

private suspend fun ApplicationCall.respondDeleteError(error: DeleteError) {
    when (error) {
        is DeleteError.Validation ->
            respond(HttpStatusCode.BadRequest, ErrorResponse("Validation failed", error.message))

        is DeleteError.NotFound ->
            respond(HttpStatusCode.NotFound)

        is DeleteError.TagConflict ->
            respond(
                HttpStatusCode.Conflict,
                TagConflictResponse(
                    error = "Conflict",
                    message = "Cannot delete version with active tags. Remove or reassign the following tags first.",
                    tags = error.tags,
                ),
            )

        is DeleteError.Database ->
            respond(HttpStatusCode.InternalServerError, ErrorResponse("Database error", error.message))
    }
}

@Suppress("LongMethod")
fun Application.configureRouting(
    versionsRepo: VersionsRepository,
    healthRepo: HealthRepository,
    auditRepo: AuditRepository,
    tagsRepo: TagsRepository,
) {
    val logger = LoggerFactory.getLogger("io.sdkman.routes.VersionRoutes")

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
                        HealthCheckResponse(HealthStatus.SUCCESS, null),
                    )
                }.getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthCheckResponse(HealthStatus.FAILURE, failure.message),
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
                val versions = versionsRepo.read(candidateId, platform, distribution, visible)
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
                val maybeVersion =
                    versionsRepo.read(
                        candidate = candidateId,
                        version = versionId,
                        platform = platform,
                        distribution = distribution,
                    )
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
                        versionsRepo.create(validVersion).fold(
                            ifLeft = { error ->
                                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Database error", error))
                            },
                            ifRight = { versionId ->
                                logAudit(logger, auditRepo, username, AuditOperation.CREATE, validVersion)
                                processTags(logger, tagsRepo, versionId, validVersion)
                                call.respond(HttpStatusCode.NoContent)
                            },
                        )
                    },
                )
            }
            delete("/versions") {
                val username = call.authenticatedUsername()
                either<DeleteError, Unit> {
                    val uniqueVersion =
                        Either
                            .catch { call.receive<UniqueVersion>() }
                            .mapLeft { DeleteError.Validation("Invalid request: ${it.message ?: "Unknown error"}") }
                            .bind()
                    val validUniqueVersion =
                        UniqueVersionValidator
                            .validate(uniqueVersion)
                            .mapLeft { DeleteError.Validation(it.message) }
                            .bind()
                    val versionToDelete =
                        versionsRepo
                            .read(
                                candidate = validUniqueVersion.candidate,
                                version = validUniqueVersion.version,
                                platform = validUniqueVersion.platform,
                                distribution = validUniqueVersion.distribution,
                            ).toEither { DeleteError.NotFound }
                            .bind()
                    val versionId =
                        versionsRepo
                            .findVersionId(validUniqueVersion)
                            .toEither { DeleteError.NotFound }
                            .bind()
                    val tagNames =
                        tagsRepo
                            .findTagNamesByVersionId(versionId)
                            .mapLeft { DeleteError.Database(it.message) }
                            .bind()
                    if (tagNames.isNotEmpty()) raise(DeleteError.TagConflict(tagNames))
                    logAudit(logger, auditRepo, username, AuditOperation.DELETE, versionToDelete)
                    val deleted = versionsRepo.delete(validUniqueVersion)
                    if (deleted == 0) raise(DeleteError.NotFound)
                }.fold(
                    ifLeft = { error -> call.respondDeleteError(error) },
                    ifRight = { call.respond(HttpStatusCode.NoContent) },
                )
            }
            delete("/versions/tags") {
                val username = call.authenticatedUsername()
                either {
                    val uniqueTag =
                        Either
                            .catch { call.receive<UniqueTag>() }
                            .mapLeft {
                                DeleteTagError.Deserialization(
                                    "Invalid request: ${it.message ?: "Unknown error"}",
                                )
                            }.bind()
                    UniqueTagValidator
                        .validate(uniqueTag)
                        .mapLeft { DeleteTagError.Validation(it) }
                        .bind()
                    val deletedCount =
                        tagsRepo
                            .deleteTag(uniqueTag)
                            .mapLeft { DeleteTagError.Database(it.message) }
                            .bind()
                    if (deletedCount == 0) raise(DeleteTagError.NotFound(uniqueTag.tag))
                    auditRepo
                        .recordAudit(username, AuditOperation.DELETE, uniqueTag)
                        .onLeft { error ->
                            logger.warn(
                                "Audit logging failed for DELETE /versions/tags: ${error.message}",
                            )
                        }
                    call.respond(HttpStatusCode.NoContent)
                }.onLeft { error ->
                    call.respondDeleteTagError(error)
                }
            }
        }
    }
}
