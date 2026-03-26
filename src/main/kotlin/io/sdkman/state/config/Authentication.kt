package io.sdkman.state.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureJwtAuthentication(config: AppConfig) =
    install(Authentication) {
        jwt("auth-jwt") {
            val algorithm = Algorithm.HMAC256(config.jwtSecret)
            val jwtVerifier =
                JWT
                    .require(algorithm)
                    .withIssuer("sdkman-state")
                    .withAudience("sdkman-state")
                    .build()
            verifier(jwtVerifier)
            validate { credential ->
                val payload = credential.payload
                if (payload.issuer == "sdkman-state" &&
                    payload.audience.contains("sdkman-state") &&
                    payload.getClaim("role").asString() != null
                ) {
                    JWTPrincipal(payload)
                } else {
                    null
                }
            }
        }
    }
