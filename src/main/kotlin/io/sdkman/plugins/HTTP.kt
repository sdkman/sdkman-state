package io.sdkman.plugins

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.cachingheaders.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.date.*
import io.sdkman.config.ApiCacheConfig
import java.time.Instant

fun Application.configureHTTP(apiCacheConfig: ApiCacheConfig) {
    install(Compression) {
        gzip {
            priority = 1.0
            matchContentType(
                ContentType.Application.Json,
            )
        }
    }
    install(CachingHeaders) {
        options { _, content ->
            when (content.contentType?.withoutParameters()) {
                ContentType.Application.Json ->
                    CachingOptions(
                        cacheControl = CacheControl.MaxAge(maxAgeSeconds = apiCacheConfig.maxAgeSeconds),
                        expires = GMTDate(Instant.now().plusSeconds(apiCacheConfig.maxAgeSeconds.toLong()).toEpochMilli()),
                    )
                else -> null
            }
        }
    }
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        staticResources("/openapi", "openapi")
    }
}
