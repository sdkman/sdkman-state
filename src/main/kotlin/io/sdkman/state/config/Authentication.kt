package io.sdkman.state.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.application.service.AuthServiceImpl
import java.util.UUID

@Suppress("NoNullableTypes")
fun Application.configureJwtAuthentication(config: AppConfig) =
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT
                    .require(Algorithm.HMAC256(config.jwtSecret))
                    .withIssuer(AuthServiceImpl.ISSUER)
                    .withAudience(AuthServiceImpl.AUDIENCE)
                    .build(),
            )
            validate { credential ->
                val role = credential.payload.getClaim("role").asString() ?: return@validate null
                val email = credential.payload.subject ?: return@validate null
                val candidates = credential.payload.getClaim("candidates")?.asList(String::class.java) ?: emptyList()
                val vendorId =
                    credential.payload.getClaim("vendor_id")?.asString()?.let {
                        runCatching { UUID.fromString(it) }.getOrNull()
                    } ?: UUID.fromString("00000000-0000-0000-0000-000000000000")
                JwtPrincipal(
                    vendorId = vendorId,
                    email = email,
                    role = role,
                    candidates = candidates,
                )
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Unauthorized", "Invalid or expired token"),
                )
            }
        }
    }
