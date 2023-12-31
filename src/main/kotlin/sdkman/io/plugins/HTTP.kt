package sdkman.io.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun Application.configureHTTP() {
    install(Compression) {
        gzip {
            priority = 1.0
            matchContentType(
                ContentType.Application.Json
            )
        }
    }
    routing {
        swaggerUI(path = "openapi")
    }
}
