package io.sdkman.state.adapter.primary.rest

import arrow.core.getOrElse
import arrow.core.toOption
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.HealthCheckResponse
import io.sdkman.state.domain.repository.HealthRepository

fun Route.healthRoutes(healthRepo: HealthRepository) {
    get("/meta/health") {
        healthRepo
            .checkDatabaseConnection()
            .map {
                call.respond(
                    HttpStatusCode.OK,
                    HealthCheckResponse("SUCCESS"),
                )
            }.getOrElse { failure ->
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    HealthCheckResponse("FAILURE", failure.message.toOption()),
                )
            }
    }
}
