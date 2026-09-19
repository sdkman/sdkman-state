package io.sdkman.state.adapter.primary.rest

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.domain.service.CandidateService

/**
 * Public, unauthenticated listing of the candidate registry.
 *
 * The route sets no `Cache-Control` header of its own, so the `CachingHeaders` plugin that
 * [versionReadRoutes] installs on the routing root applies the same `max-age` the version read
 * routes carry. The spec requires the two public read surfaces to cache alike.
 *
 * The order of the response is part of the contract — the Candidates Service renders `sdk list`
 * straight from it — so the service result is mapped as it arrives and never re-sorted here.
 */
fun Route.candidateReadRoute(candidateService: CandidateService) {
    get("/candidates") {
        candidateService.list().fold(
            ifLeft = { error -> call.respondDomainError(error) },
            ifRight = { candidates ->
                call.respond(
                    HttpStatusCode.OK,
                    candidates.map { (candidate, default) -> candidate.toDto(default) },
                )
            },
        )
    }
}
