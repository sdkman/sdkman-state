package io.sdkman.state.adapter.primary.rest

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.domain.service.CandidateService

// The candidate routes open their own `routing` block rather than joining
// `configureRouting`, which already takes seven parameters and reports under detekt's
// `LongParameterList` at eight. `configureHTTP` opens a second block the same way.
// Ktor returns the same routing root either way, so the root-installed `CachingHeaders`
// plugin still covers `GET /candidates`.
fun Application.configureCandidateRouting(candidateService: CandidateService) {
    routing {
        candidateReadRoutes(candidateService)
        authenticate("auth-jwt") {
            adminCreateCandidateRoute(candidateService)
        }
    }
}

fun Route.candidateReadRoutes(candidateService: CandidateService) {
    listCandidatesRoute(candidateService)
}

// Public and unauthenticated, like the version read routes. The service already orders
// the rows ascending by identifier and that order is part of the contract, so the
// mapping preserves it and never re-sorts.
private fun Route.listCandidatesRoute(candidateService: CandidateService) {
    get("/candidates") {
        candidateService.findAll().fold(
            ifLeft = { domainError -> call.respondDomainError(domainError) },
            ifRight = { listings -> call.respond(HttpStatusCode.OK, listings.map { it.toDto() }) },
        )
    }
}
