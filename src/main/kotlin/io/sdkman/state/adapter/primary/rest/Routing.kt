package io.sdkman.state.adapter.primary.rest

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.sdkman.state.domain.repository.HealthRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.VersionService

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
