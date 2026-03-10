package io.sdkman.plugins

import arrow.core.*
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
import io.sdkman.domain.UniqueVersion
import io.sdkman.repos.VersionsRepository
import io.sdkman.validation.UniqueVersionValidator
import io.sdkman.validation.ValidationErrorResponse
import io.sdkman.validation.ValidationFailure
import io.sdkman.validation.VersionRequestValidator
import kotlinx.serialization.Serializable
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
                val principal = call.principal<UserIdPrincipal>()
                val username = principal?.name ?: "unknown"
                val requestBody = call.receiveText()
                VersionRequestValidator
                    .validateRequest(requestBody)
                    .map { validVersion ->
                        versionsRepo
                            .create(validVersion)
                            .also {
                                auditRepo
                                    .recordAudit(username, AuditOperation.CREATE, validVersion)
                                    .onLeft { auditError ->
                                        logger.warn("Audit logging failed for POST /versions: ${auditError.message}", auditError)
                                    }
                            }.map { versionId ->
                                validVersion.tags.onSome { tagList ->
                                    tagsRepo
                                        .replaceTags(
                                            versionId = versionId,
                                            candidate = validVersion.candidate,
                                            distribution = validVersion.distribution,
                                            platform = validVersion.platform,
                                            tags = tagList,
                                        ).onLeft { tagError ->
                                            logger.warn("Tag processing failed for POST /versions: ${tagError.message}", tagError)
                                        }
                                }
                                call.respond(HttpStatusCode.NoContent)
                            }.getOrElse { error ->
                                val errorResponse = ErrorResponse("Database error", error)
                                call.respond(HttpStatusCode.InternalServerError, errorResponse)
                            }
                    }.getOrElse { errors ->
                        val failures = errors.map { ValidationFailure(it.field, it.message) }
                        val errorResponse = ValidationErrorResponse("Validation failed", failures)
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    }
            }
            delete("/versions") {
                val principal = call.principal<UserIdPrincipal>()
                val username = principal?.name ?: "unknown"
                Either
                    .catch { call.receive<UniqueVersion>() }
                    .mapLeft { io.sdkman.validation.InvalidRequestError(it.message ?: "Unknown error") }
                    .flatMap { UniqueVersionValidator.validate(it) }
                    .map { validUniqueVersion ->
                        val maybeVersion =
                            versionsRepo.read(
                                candidate = validUniqueVersion.candidate,
                                version = validUniqueVersion.version,
                                platform = validUniqueVersion.platform,
                                distribution = validUniqueVersion.distribution,
                            )
                        maybeVersion
                            .map { versionToDelete ->
                                versionsRepo
                                    .findVersionId(validUniqueVersion)
                                    .map { versionId ->
                                        tagsRepo
                                            .findTagNamesByVersionId(versionId)
                                            .map { tagNames ->
                                                when {
                                                    tagNames.isNotEmpty() ->
                                                        call.respond(
                                                            HttpStatusCode.Conflict,
                                                            TagConflictResponse(
                                                                error = "Conflict",
                                                                message =
                                                                    "Cannot delete version with active tags. Remove or reassign the following tags first.",
                                                                tags = tagNames,
                                                            ),
                                                        )
                                                    else -> {
                                                        auditRepo
                                                            .recordAudit(username, AuditOperation.DELETE, versionToDelete)
                                                            .onLeft { auditError ->
                                                                logger.warn(
                                                                    "Audit logging failed for DELETE /versions: ${auditError.message}",
                                                                    auditError,
                                                                )
                                                            }
                                                        when (versionsRepo.delete(validUniqueVersion)) {
                                                            1 -> call.respond(HttpStatusCode.NoContent)
                                                            0 -> call.respond(HttpStatusCode.NotFound)
                                                        }
                                                    }
                                                }
                                            }.getOrElse { error ->
                                                logger.warn("Tag check failed for DELETE /versions: ${error.message}", error)
                                                call.respond(
                                                    HttpStatusCode.InternalServerError,
                                                    ErrorResponse("Database error", error.message),
                                                )
                                            }
                                    }.getOrElse {
                                        call.respond(HttpStatusCode.NotFound)
                                    }
                            }.getOrElse { call.respond(HttpStatusCode.NotFound) }
                    }.getOrElse { error ->
                        val errorResponse = ErrorResponse("Validation failed", error.message)
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    }
            }
        }
    }
}
