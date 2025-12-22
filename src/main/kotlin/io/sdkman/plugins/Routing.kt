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
import io.sdkman.domain.UniqueVersion
import io.sdkman.repos.VersionsRepository
import io.sdkman.validation.ValidationErrorResponse
import io.sdkman.validation.ValidationFailure
import io.sdkman.validation.VersionRequestValidator
import io.sdkman.validation.UniqueVersionValidator
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

@Serializable
data class HealthCheckResponse(
    val status: HealthStatus,
    val message: String? = null
)

fun Application.configureRouting(
    repo: VersionsRepository,
    healthRepo: HealthRepository,
    auditRepo: AuditRepository
) {
    val logger = LoggerFactory.getLogger("io.sdkman.routes.VersionRoutes")

    fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
        when (this.queryParameters["visible"].toOption()) {
            Some("all") -> None
            Some("false") -> Some(false)
            Some("true") -> Some(true)
            else -> Some(true)
        }

    fun String.toDistribution(): Option<Distribution> =
        Distribution.entries.firstOrNone { it.name == this }

    routing {
        get("/meta/health") {
            healthRepo.checkDatabaseConnection()
                .map {
                    call.respond(
                        HttpStatusCode.OK,
                        HealthCheckResponse(HealthStatus.SUCCESS, null)
                    )
                }
                .getOrElse { failure ->
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        HealthCheckResponse(HealthStatus.FAILURE, failure.message)
                    )
                }
        }
        get("/versions/{candidate}") {
            option {
                val candidateId = call.parameters["candidate"].toOption()
                    .filter { it.isNotBlank() }.bind()
                val visible = call.request.visibleQueryParam()
                val platform = call.request.queryParameters["platform"].toOption()
                    .map { Platform.findByPlatformId(it) }
                val distribution = call.request.queryParameters["distribution"].toOption()
                    .flatMap { it.toDistribution() }
                val versions = repo.read(candidateId, platform, distribution, visible)
                call.respond(HttpStatusCode.OK, versions)
            }.getOrElse {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
        get("/versions/{candidate}/{version}") {
            option {
                val candidateId = call.parameters["candidate"].toOption()
                    .filter { it.isNotBlank() }.bind()
                val versionId = call.parameters["version"].toOption()
                    .filter { it.isNotBlank() }.bind()
                val platform = call.request.queryParameters["platform"].toOption()
                    .map { Platform.findByPlatformId(it) }
                    .getOrElse { Platform.UNIVERSAL }
                val distribution = call.request.queryParameters["distribution"].toOption()
                    .flatMap { it.toDistribution() }
                val maybeVersion = repo.read(
                    candidate = candidateId,
                    version = versionId,
                    platform = platform,
                    distribution = distribution
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
                VersionRequestValidator.validateRequest(requestBody)
                        .map { validVersion ->
                            repo.create(validVersion)
                                .also {
                                    auditRepo.recordAudit(username, AuditOperation.CREATE, validVersion)
                                        .onLeft { auditError ->
                                            logger.warn("Audit logging failed for POST /versions: ${auditError.message}", auditError)
                                        }
                                }
                                .map { call.respond(HttpStatusCode.NoContent) }
                                .getOrElse { error ->
                                    val errorResponse = ErrorResponse("Database error", error)
                                    call.respond(HttpStatusCode.InternalServerError, errorResponse)
                                }
                        }
                        .getOrElse { errors ->
                            val failures = errors.map { ValidationFailure(it.field, it.message) }
                            val errorResponse = ValidationErrorResponse("Validation failed", failures)
                            call.respond(HttpStatusCode.BadRequest, errorResponse)
                        }
            }
            delete("/versions") {
                val principal = call.principal<UserIdPrincipal>()
                val username = principal?.name ?: "unknown"
                Either.catch { call.receive<UniqueVersion>() }
                    .mapLeft { io.sdkman.validation.InvalidRequestError(it.message ?: "Unknown error") }
                    .flatMap { UniqueVersionValidator.validate(it) }
                    .map { validUniqueVersion ->
                        val maybeVersion = repo.read(
                            candidate = validUniqueVersion.candidate,
                            version = validUniqueVersion.version,
                            platform = validUniqueVersion.platform,
                            distribution = validUniqueVersion.distribution
                        )
                        maybeVersion
                            .also { versionOption ->
                                versionOption.map { versionToDelete ->
                                    auditRepo.recordAudit(username, AuditOperation.DELETE, versionToDelete)
                                        .onLeft { auditError ->
                                            logger.warn("Audit logging failed for DELETE /versions: ${auditError.message}", auditError)
                                        }
                                }
                            }
                            .map {
                                when (repo.delete(validUniqueVersion)) {
                                    1 -> call.respond(HttpStatusCode.NoContent)
                                    0 -> call.respond(HttpStatusCode.NotFound)
                                }
                            }
                            .getOrElse { call.respond(HttpStatusCode.NotFound) }
                    }
                    .getOrElse { error ->
                        val errorResponse = ErrorResponse("Validation failed", error.message)
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    }
            }
        }
    }
}
