package io.sdkman.state.adapter.primary.rest

import io.ktor.http.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.LoginRequest
import io.sdkman.state.adapter.primary.rest.dto.LoginResponse
import io.sdkman.state.domain.error.AuthError
import io.sdkman.state.domain.service.AuthService

fun Route.loginRoute(authService: AuthService) {
    post("/login") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val request = call.receive<LoginRequest>()
        val clientIp = call.request.origin.remoteHost
        authService.login(request.email, request.password, clientIp).fold(
            ifLeft = { error ->
                when (error) {
                    is AuthError.InvalidCredentials ->
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", "Invalid credentials"),
                        )

                    is AuthError.RateLimitExceeded ->
                        call.respond(
                            HttpStatusCode.TooManyRequests,
                            ErrorResponse("Too Many Requests", "Rate limit exceeded"),
                        )

                    is AuthError.TokenCreationFailed ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Internal Server Error", "Token creation failed"),
                        )
                }
            },
            ifRight = { token ->
                call.respond(HttpStatusCode.OK, LoginResponse(token))
            },
        )
    }
}
