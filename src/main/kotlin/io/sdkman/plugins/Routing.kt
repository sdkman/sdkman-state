package io.sdkman.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.sdkman.domain.HealthRepository
import io.sdkman.domain.TagService
import io.sdkman.domain.VersionService

fun Application.configureRouting(
    versionService: VersionService,
    tagService: TagService,
    healthRepo: HealthRepository,
) {
    routing {
        healthRoutes(healthRepo)
        versionReadRoutes(versionService)
        authenticate("auth-basic") {
            versionWriteRoutes(versionService)
            tagRoutes(tagService)
        }
    }
}
