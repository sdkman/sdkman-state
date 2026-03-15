package io.sdkman.state

import io.ktor.server.application.*
import io.sdkman.state.adapter.primary.rest.*
import io.sdkman.state.adapter.secondary.persistence.AuditRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.HealthRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.TagsRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.VersionsRepository
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.configureAppConfig
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

    val versionsRepo = VersionsRepository()
    val tagsRepo = TagsRepositoryImpl()
    val auditRepo = AuditRepositoryImpl()

    configureRouting(
        versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
        tagService = TagServiceImpl(tagsRepo, auditRepo),
        healthRepo = HealthRepositoryImpl(),
    )
}
