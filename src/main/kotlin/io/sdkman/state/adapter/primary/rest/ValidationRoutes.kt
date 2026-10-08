package io.sdkman.state.adapter.primary.rest

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.sdkman.state.application.validation.VersionRequestValidator

// Public twins of POST /versions and POST /admin/candidates: they run the same validation
// answer as the write routes, but never authenticate and never write (specs/public-validation.md).
fun Route.validationRoutes(versionRequestValidator: VersionRequestValidator) {
    route("/validate") {
        post("/versions") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            versionValidationAnswer(versionRequestValidator, call.receiveText()).fold(
                ifLeft = { rejection -> call.respondRejection(rejection) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }
        post("/candidates") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            candidateValidationAnswer(call.receiveText()).fold(
                ifLeft = { rejection -> call.respondRejection(rejection) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }
    }
}
