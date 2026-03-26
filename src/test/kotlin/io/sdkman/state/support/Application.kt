package io.sdkman.state.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureHTTP
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureDatabase
import io.sdkman.state.config.configureJwtAuthentication

fun testApplicationConfig(): MapApplicationConfig =
    MapApplicationConfig(
        "database.host" to PostgresTestContainer.host,
        "database.port" to PostgresTestContainer.port.toString(),
        "database.username" to PostgresTestContainer.username,
        "database.password" to PostgresTestContainer.password,
        "api.cache.control" to "600",
        "admin.email" to JwtTestSupport.ADMIN_EMAIL,
        "admin.password" to "testadminpassword",
        "jwt.secret" to JwtTestSupport.TEST_SECRET,
        "jwt.expiry" to "10",
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
        fn(this)
    }
}
