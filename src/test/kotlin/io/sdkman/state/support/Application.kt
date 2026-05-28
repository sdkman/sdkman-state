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

/**
 * Builds the default test `ApplicationConfig`, intentionally mirroring production-relevant settings
 * (notably `validation.semverish.candidates = "java"`) so acceptance tests run against the same
 * enforcement rules as the deployed service. Callers can pass `overrides` to replace any default
 * entry — e.g. an empty opt-in set, or a different candidate — without having to rebuild the whole
 * map. Overrides win on key collision; unknown keys are simply added.
 */
fun testApplicationConfig(overrides: Map<String, String> = emptyMap()): MapApplicationConfig {
    val defaults =
        mapOf(
            "database.host" to PostgresTestContainer.host,
            "database.port" to PostgresTestContainer.port.toString(),
            "database.username" to PostgresTestContainer.username,
            "database.password" to PostgresTestContainer.password,
            "api.cache.control" to "600",
            "admin.email" to JwtTestSupport.ADMIN_EMAIL,
            "admin.password" to "testadminpassword",
            "jwt.secret" to JwtTestSupport.TEST_SECRET,
            "jwt.expiry" to "10",
            "validation.semverish.candidates" to "java",
        )
    return MapApplicationConfig((defaults + overrides).toList())
}

/**
 * Spins up a `testApplication` wired with the project's full DI graph. `configOverrides` is merged
 * into [testApplicationConfig] so individual specs can opt different candidates in or out of strict
 * semverish validation without forking the harness. Existing call sites — `withTestApplication { ... }`
 * — continue to work unchanged because the trailing lambda still binds to [fn].
 */
fun withTestApplication(
    configOverrides: Map<String, String> = emptyMap(),
    fn: suspend (ApplicationTestBuilder.() -> Unit),
) {
    testApplication {
        environment {
            config = testApplicationConfig(configOverrides)
        }
        application {
            val appConfig = DefaultAppConfig(environment.config)
            configureDatabase(appConfig)
            configureHTTP()
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
