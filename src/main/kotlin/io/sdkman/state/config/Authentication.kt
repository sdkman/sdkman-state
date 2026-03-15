package io.sdkman.state.config

import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureBasicAuthentication(config: ApiAuthenticationConfig) =
    install(Authentication) {
        basic("auth-basic") {
            realm = "Access to the '/' path"
            validate { credentials ->
                if (credentials.name == config.username && credentials.password == config.password) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
