package io.sdkman.state

import io.ktor.server.application.*
import io.sdkman.state.adapter.primary.rest.*
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.*

fun main(args: Array<String>) =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val appConfig = DefaultAppConfig(environment.config)

    configureDatabaseMigration(appConfig)
    configureDatabase(appConfig)

    configureHTTP(appConfig)
    configureSerialization()
    configureJwtAuthentication(appConfig)

    val versionsRepo = PostgresVersionRepository()
    val tagsRepo = PostgresTagRepository()
    val auditRepo = PostgresAuditRepository()
    val vendorRepo = PostgresVendorRepository()
    val tagService = TagServiceImpl(tagsRepo, auditRepo)
    val rateLimiter = RateLimiter()
    val authService = AuthServiceImpl(vendorRepo, appConfig, rateLimiter)

    configureRouting(
        versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo),
        tagService = tagService,
        healthRepo = PostgresHealthRepository(),
        authService = authService,
        vendorRepository = vendorRepo,
        appConfig = appConfig,
    )
}
