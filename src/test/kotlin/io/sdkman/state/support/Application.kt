package io.sdkman.state.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureHTTP
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureBasicAuthentication
import io.sdkman.state.config.configureDatabase

fun testApplicationConfig(): MapApplicationConfig =
    MapApplicationConfig(
        "database.host" to PostgresTestContainer.host,
        "database.port" to PostgresTestContainer.port.toString(),
        "database.username" to PostgresTestContainer.username,
        "database.password" to PostgresTestContainer.password,
        "api.username" to "testuser",
        "api.password" to "password123",
        "api.cache.control" to "600",
    )

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    testApplication {
        environment {
            config = testApplicationConfig()
        }
        application {
            val appConfig = DefaultAppConfig(environment.config)
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
        fn(this)
    }
}
