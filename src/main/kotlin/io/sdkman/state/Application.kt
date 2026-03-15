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

fun main(args: Array<String>) =
    io.ktor.server.netty.EngineMain
        .main(args)

fun Application.module() {
    val appConfig = DefaultAppConfig(environment.config)

    configureDatabaseMigration(appConfig)
    configureDatabase(appConfig)

    configureHTTP(appConfig)
    configureSerialization()
    configureBasicAuthentication(appConfig)

    val versionsRepo = PostgresVersionRepository()
    val tagsRepo = PostgresTagRepository()
    val auditRepo = PostgresAuditRepository()

    configureRouting(
        versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
        tagService = TagServiceImpl(tagsRepo, auditRepo),
        healthRepo = PostgresHealthRepository(),
    )
}
