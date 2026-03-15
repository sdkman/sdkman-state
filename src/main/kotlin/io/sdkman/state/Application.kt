package io.sdkman.state

import io.ktor.server.application.*
import io.sdkman.state.adapter.primary.rest.*
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.*
import io.sdkman.state.plugins.*

fun main(args: Array<String>) =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val appConfig = configureAppConfig(environment)

    configureDatabaseMigration(appConfig.databaseConfig)
    configureDatabase(appConfig.databaseConfig)

    configureHTTP(appConfig.apiCacheConfig)
    configureSerialization()
    configureBasicAuthentication(appConfig.apiAuthenticationConfig)

    val versionsRepo = PostgresVersionRepository()
    val tagsRepo = PostgresTagRepository()
    val auditRepo = PostgresAuditRepository()

    configureRouting(
        versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
        tagService = TagServiceImpl(tagsRepo, auditRepo),
        healthRepo = PostgresHealthRepository(),
    )
}
