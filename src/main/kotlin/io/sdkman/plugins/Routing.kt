package io.sdkman.plugins

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
            val candidate = call.parameters["candidate"] ?: throw IllegalArgumentException("Candidate not found")
            val versions = repo.read(candidate)
            call.respond(HttpStatusCode.OK, versions)
        }
        authenticate("auth-basic") {
            post("/versions") {
                call.receive<Version>().let { version ->
                    repo.create(version)
                }.also {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            delete("/versions") {
                call.receive<UniqueVersion>().let {
                    repo.delete(it)
                }.also {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
