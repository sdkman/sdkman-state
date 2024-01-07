package io.sdkman.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.domain.CandidateVersion
import io.sdkman.repos.CandidateVersionsRepository

fun Application.configureRouting(repo: CandidateVersionsRepository) {
    routing {
            get("/versions/{candidate}") {
                val candidate = call.parameters["candidate"] ?: throw IllegalArgumentException("Candidate not found")
                val versions = repo.read(candidate)
                call.respond(HttpStatusCode.OK, versions)
            }
            post("/versions") {
                call.receive<CandidateVersion>().let { version ->
                    repo.create(version)
                }.also {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
    }
}
