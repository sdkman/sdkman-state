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

const val VALIDATION_BODY_LIMIT_BYTES = 16_384L

private val NoStoreCaching =
    createRouteScopedPlugin("NoStoreCaching") {
        onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-store") }
    }

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
