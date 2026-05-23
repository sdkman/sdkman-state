package io.sdkman.state.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureHTTP
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
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
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.config.createHikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import java.sql.Connection

fun testApplicationConfig(): MapApplicationConfig =
    MapApplicationConfig(
        "database.host" to PostgresTestContainer.host,
        "database.port" to PostgresTestContainer.port.toString(),
        "database.name" to "sdkman",
        "database.username" to PostgresTestContainer.username,
        "database.password" to PostgresTestContainer.password,
        "database.pool.maxSize" to "4",
        "database.pool.minIdle" to "0",
        "database.pool.connectionTimeoutMs" to "5000",
        "database.pool.maxLifetimeMs" to "60000",
        "database.pool.idleTimeoutMs" to "10000",
        "api.cache.control" to "600",
        "admin.email" to JwtTestSupport.ADMIN_EMAIL,
        "admin.password" to "testadminpassword",
        "jwt.secret" to JwtTestSupport.TEST_SECRET,
        "jwt.expiry" to "10",
        "validation.semverish.candidates" to "java",
    )

private val sharedTestAppConfig by lazy { DefaultAppConfig(testApplicationConfig()) }

val sharedTestDataSource by lazy {
    val dataSource = createHikariDataSource(sharedTestAppConfig)
    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })
    dataSource
}

val sharedTestDatabase: Database by lazy {
    Database.connect(
        datasource = sharedTestDataSource,
        databaseConfig =
            DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            },
    )
}

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    sharedTestDatabase
    testApplication {
        environment {
            config = testApplicationConfig()
        }
        application {
            configureHTTP()
            configureSerialization()
            configureJwtAuthentication(sharedTestAppConfig)

            val versionsRepo = PostgresVersionRepository()
            val tagsRepo = PostgresTagRepository()
            val auditRepo = PostgresAuditRepository()
            val vendorRepo = PostgresVendorRepository()
            val tagService = TagServiceImpl(tagsRepo, auditRepo)
            val transactional = ExposedTransactional()
            val rateLimiter = RateLimiter()
            val authService = AuthServiceImpl(vendorRepo, sharedTestAppConfig, rateLimiter)

            val versionRequestValidator = VersionRequestValidator(sharedTestAppConfig.semverishCandidates)

            configureRouting(
                versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
                tagService = tagService,
                healthRepo = PostgresHealthRepository(),
                authService = authService,
                vendorRepository = vendorRepo,
                appConfig = sharedTestAppConfig,
                versionRequestValidator = versionRequestValidator,
            )
        }
        fn(this)
    }
}
