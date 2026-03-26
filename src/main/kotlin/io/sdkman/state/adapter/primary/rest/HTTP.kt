package io.sdkman.state.adapter.primary.rest

import arrow.core.toOption
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.*
import io.sdkman.state.config.AppConfig
import java.time.Instant

fun Application.configureHTTP(config: AppConfig) {
    install(Compression) {
        gzip {
            priority = 1.0
            matchContentType(
                ContentType.Application.Json,
            )
        }
    }
    install(XForwardedHeaders)
    install(CachingHeaders) {
        options { _, content ->
            content.contentType
                .toOption()
                .map { it.withoutParameters() }
                .filter { it == ContentType.Application.Json }
                .map {
                    CachingOptions(
                        cacheControl = CacheControl.MaxAge(maxAgeSeconds = config.cacheMaxAge),
                        expires = GMTDate(Instant.now().plusSeconds(config.cacheMaxAge.toLong()).toEpochMilli()),
                    )
                }.getOrNull()
        }
    }
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        staticResources("/openapi", "openapi")
    }
}
