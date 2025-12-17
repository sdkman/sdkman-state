package io.sdkman.plugins

import arrow.core.*
import arrow.core.raise.option
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.Distribution
import io.sdkman.domain.HealthStatus
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.domain.HealthRepository
import io.sdkman.repos.VersionsRepository
import io.sdkman.validation.VersionValidator
import kotlinx.serialization.Serializable

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

fun Application.configureRouting(repo: VersionsRepository, healthRepo: HealthRepository) {

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
                .fold(
                    { failure ->
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            HealthCheckResponse(HealthStatus.FAILURE, failure.message)
                        )
                    },
                    {
                        call.respond(
                            HttpStatusCode.OK,
                            HealthCheckResponse(HealthStatus.SUCCESS, null)
                        )
                    }
                )
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
                maybeVersion.fold(
                    { call.respond(HttpStatusCode.NotFound) },
                    { call.respond(HttpStatusCode.OK, it) }
                )
            }.getOrElse { call.respond(HttpStatusCode.BadRequest) }
        }
        authenticate("auth-basic") {
            post("/versions") {
                Either.catch { call.receive<Version>() }
                    .mapLeft { io.sdkman.validation.InvalidRequestError(it.message ?: "Unknown error") }
                    .flatMap { version ->
                        application.log.info(
                            "Received POST for new version release: candidate=${version.candidate}, " +
                            "version=${version.version}, platform=${version.platform}, distribution=${version.distribution.getOrElse { "none" }}"
                        )
                        VersionValidator.validateVersion(version)
                    }
                    .fold(
                        { error ->
                            val errorResponse = ErrorResponse("Validation failed", error.message)
                            call.respond(HttpStatusCode.BadRequest, errorResponse)
                        },
                        { validVersion ->
                            repo.create(validVersion)
                                .map { call.respond(HttpStatusCode.NoContent) }
                                .getOrElse { error ->
                                    val errorResponse = ErrorResponse("Database error", error)
                                    call.respond(HttpStatusCode.InternalServerError, errorResponse)
                                }
                        }
                    )
            }
            delete("/versions") {
                Either.catch { call.receive<UniqueVersion>() }
                    .mapLeft { io.sdkman.validation.InvalidRequestError(it.message ?: "Unknown error") }
                    .flatMap { uniqueVersion ->
                        VersionValidator.validateUniqueVersion(uniqueVersion)
                    }
                    .fold(
                        { error ->
                            val errorResponse = ErrorResponse("Validation failed", error.message)
                            call.respond(HttpStatusCode.BadRequest, errorResponse)
                        },
                        { validUniqueVersion ->
                            when (repo.delete(validUniqueVersion)) {
                                1 -> call.respond(HttpStatusCode.NoContent)
                                0 -> call.respond(HttpStatusCode.NotFound)
                            }
                        }
                    )
            }
        }
    }
}
