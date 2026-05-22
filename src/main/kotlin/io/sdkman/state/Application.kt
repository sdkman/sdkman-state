package io.sdkman.state

import io.ktor.server.application.*
import io.sdkman.state.adapter.primary.rest.*
import io.sdkman.state.adapter.secondary.persistence.ExposedTransactional
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(args: Array<String>) =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val appConfig = DefaultAppConfig(environment.config)

    val dataSource = createHikariDataSource(appConfig)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    configureDatabaseMigration(dataSource)
    configureDatabase(dataSource)

    configureHTTP()
    configureSerialization()
    configureJwtAuthentication(appConfig)

    val versionsRepo = PostgresVersionRepository()
    val tagsRepo = PostgresTagRepository()
    val auditRepo = PostgresAuditRepository()
    val vendorRepo = PostgresVendorRepository()
    val tagService = TagServiceImpl(tagsRepo, auditRepo)
    val transactional = ExposedTransactional()
    val rateLimiter = RateLimiter()
    launch {
        while (true) {
            delay(60_000)
            rateLimiter.cleanup()
        }
    }
    val authService = AuthServiceImpl(vendorRepo, appConfig, rateLimiter)

    val versionRequestValidator = VersionRequestValidator(appConfig.semverishCandidates)

    configureRouting(
        versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
        tagService = tagService,
        healthRepo = PostgresHealthRepository(),
        authService = authService,
        vendorRepository = vendorRepo,
        appConfig = appConfig,
        versionRequestValidator = versionRequestValidator,
    )
}
