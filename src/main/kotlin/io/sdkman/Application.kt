package io.sdkman

import io.ktor.server.application.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.*
import io.sdkman.repos.AuditRepositoryImpl
import io.sdkman.repos.HealthRepositoryImpl
import io.sdkman.repos.TagsRepositoryImpl
import io.sdkman.repos.VersionsRepository
import io.sdkman.service.TagServiceImpl
import io.sdkman.service.VersionServiceImpl

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
