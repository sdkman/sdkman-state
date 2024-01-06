package io.sdkman.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.repos.CandidateVersionsRepository

fun Application.configureRouting(repo: CandidateVersionsRepository) {
    routing {
        get("/candidates/{candidate}") {
            val candidate = call.parameters["candidate"] ?: throw IllegalArgumentException("Candidate not found")
            val versions = repo.read(candidate)
            call.respond(HttpStatusCode.OK, versions)
        }
    }
}