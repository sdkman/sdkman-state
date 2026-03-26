package io.sdkman.state.adapter.primary.rest

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.repository.HealthRepository
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.domain.service.AuthService
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.VersionService

fun Application.configureRouting(
    versionService: VersionService,
    tagService: TagService,
    healthRepo: HealthRepository,
    authService: AuthService,
    vendorRepository: VendorRepository,
    appConfig: AppConfig,
) {
    routing {
        healthRoutes(healthRepo)
        versionReadRoutes(versionService)
        loginRoute(authService)
        authenticate("auth-jwt") {
            versionWriteRoutes(versionService)
            tagRoutes(tagService)
            adminListVendorsRoute(vendorRepository)
            adminCreateVendorRoute(vendorRepository, appConfig)
            adminDeleteVendorRoute(vendorRepository)
        }
    }
}
