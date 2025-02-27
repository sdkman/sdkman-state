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
            call.parameters["candidate"].toOption().map { candidateId ->
                val visible = call.request.visibleQueryParam()
                val versions = repo.read(candidateId, visible)
                call.respond(HttpStatusCode.OK, versions)
            }.getOrElse {
                throw IllegalArgumentException("Candidate not found")
            }
        }
        get("/versions/{candidate}/{platform}") {
            option {
                val candidateId = call.parameters["candidate"].toOption().bind()
                val platformId = call.parameters["platform"].toOption().bind()
                val visible = call.request.visibleQueryParam()
                val versions = repo.read(candidateId, Platform.findByPlatformId(platformId), visible)
                call.respond(HttpStatusCode.OK, versions)
            }.getOrElse {
                throw IllegalArgumentException("Candidate or platform not found")
            }
        }
        authenticate("auth-basic") {
            post("/versions") {
                call.receive<Version>()
                    .toOption()
                    .map { repo.create(it) }
                    .map { call.respond(HttpStatusCode.NoContent) }
            }
            delete("/versions") {
                call.receive<UniqueVersion>()
                    .toOption()
                    .map { repo.delete(it) }
                    .map { call.respond(HttpStatusCode.NoContent) }
            }
        }
    }
}
