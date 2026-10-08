package io.sdkman.state.adapter.primary.rest

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.sdkman.state.application.validation.VersionRequestValidator

// Validation routes need no login, so they cap what an anonymous caller can make the server read.
// Write routes are not capped (specs/public-validation.md, Business Rules item 5).
const val VALIDATION_BODY_LIMIT_BYTES = 16_384L

// Set on every call before the handler runs, so a 413 from the body limit is never cached either.
private val NoStoreCaching =
    createRouteScopedPlugin("NoStoreCaching") {
        onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
    }

// Public twins of POST /versions and POST /admin/candidates: they run the same validation
// answer as the write routes, but never authenticate and never write (specs/public-validation.md).
fun Route.validationRoutes(versionRequestValidator: VersionRequestValidator) {
    route("/validate") {
        install(NoStoreCaching)
        install(RequestBodyLimit) { bodyLimit { VALIDATION_BODY_LIMIT_BYTES } }
        post("/versions") {
            versionValidationAnswer(versionRequestValidator, call.receiveText()).fold(
                ifLeft = { rejection -> call.respondRejection(rejection) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }
        post("/candidates") {
            candidateValidationAnswer(call.receiveText()).fold(
                ifLeft = { rejection -> call.respondRejection(rejection) },
                ifRight = { call.respond(HttpStatusCode.NoContent) },
            )
        }
    }
}
