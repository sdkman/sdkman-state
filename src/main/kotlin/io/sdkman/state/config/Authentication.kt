package io.sdkman.state.config

import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureBasicAuthentication(config: AppConfig) =
    install(Authentication) {
        basic("auth-basic") {
            realm = "Access to the '/' path"
            validate { credentials ->
                if (credentials.name == config.authUsername && credentials.password == config.authPassword) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }
