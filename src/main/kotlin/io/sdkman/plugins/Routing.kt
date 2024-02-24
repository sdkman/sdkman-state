package io.sdkman.plugins

import arrow.core.getOrElse
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.Version
import io.sdkman.domain.UniqueVersion
import io.sdkman.repos.VersionsRepository

fun Application.configureRouting(repo: VersionsRepository) {
    routing {
        get("/versions/{candidate}") {
            call.parameters["candidate"].toOption().map { candidate ->
                val versions = repo.read(candidate)
                call.respond(HttpStatusCode.OK, versions)
            }.getOrElse {
                throw IllegalArgumentException("Candidate not found")
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
