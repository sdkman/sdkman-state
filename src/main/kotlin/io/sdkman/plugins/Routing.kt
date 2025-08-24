package io.sdkman.plugins

import arrow.core.*
import arrow.core.raise.option
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.repos.VersionsRepository
import io.sdkman.validation.VersionValidator
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)

fun Application.configureRouting(repo: VersionsRepository) {

    fun ApplicationRequest.visibleQueryParam(): Option<Boolean> =
        when (this.queryParameters["visible"].toOption()) {
            Some("all") -> None
            Some("false") -> Some(false)
            Some("true") -> Some(true)
            else -> Some(true)
        }

    routing {
        get("/versions/{candidate}") {
            option {
                val candidateId = call.parameters["candidate"].toOption()
                    .filter { it.isNotBlank() }.bind()
                val visible = call.request.visibleQueryParam()
                val platform = call.request.queryParameters["platform"].toOption()
                    .map { Platform.findByPlatformId(it) }
                val vendor = call.request.queryParameters["vendor"].toOption()
                val versions = repo.read(candidateId, platform, vendor, visible)
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
                val vendor = call.request.queryParameters["vendor"].toOption()
                val maybeVersion = repo.read(
                    candidate = candidateId,
                    version = versionId,
                    platform = platform,
                    vendor = vendor
                )
                maybeVersion.fold(
                    { call.respond(HttpStatusCode.NotFound) },
                    { call.respond(HttpStatusCode.OK, it) }
                )
            }.getOrElse { call.respond(HttpStatusCode.BadRequest) }
        }
        authenticate("auth-basic") {
            post("/versions") {
                val version = call.receive<Version>()
                VersionValidator.validateVersion(version)
                    .map { validVersion ->
                        repo.create(validVersion)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    .getOrElse { error ->
                        val errorResponse = ErrorResponse("Validation failed", error.message)
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    }
            }
            delete("/versions") {
                val uniqueVersion = call.receive<UniqueVersion>()
                VersionValidator.validateUniqueVersion(uniqueVersion)
                    .map { validUniqueVersion ->
                        repo.delete(validUniqueVersion)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    .getOrElse { error ->
                        val errorResponse = ErrorResponse("Validation failed", error.message)
                        call.respond(HttpStatusCode.BadRequest, errorResponse)
                    }
            }
        }
    }
}
