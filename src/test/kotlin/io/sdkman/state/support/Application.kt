package io.sdkman.state.support

import io.ktor.server.application.Application
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureCandidateRouting
import io.sdkman.state.adapter.primary.rest.configureHTTP
import io.sdkman.state.adapter.primary.rest.configureJavaRouting
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.secondary.persistence.ExposedTransactional
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresCandidateRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.CandidateServiceImpl
import io.sdkman.state.application.service.JavaVersionServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.RefreshingCandidateAllowList
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.application.validation.JavaVersionRequestValidator
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.AppConfig
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.config.createHikariDataSource
import io.sdkman.state.domain.service.CandidateAllowList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection

fun testApplicationConfig(): MapApplicationConfig =
    testApplicationConfigWithoutCommunityAccount().apply {
        put("community.email", JwtTestSupport.COMMUNITY_EMAIL)
        put("community.password", JwtTestSupport.COMMUNITY_PASSWORD)
    }

fun testApplicationConfigWithoutCommunityAccount(): MapApplicationConfig =
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
        "validation.semverish.enabled" to "true",
        "auth.rateLimit.enabled" to "true",
        "auth.bcrypt.cost" to "4",
        "candidates.refresh.intervalMs" to "300000",
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

fun withTestApplication(
    candidateAllowList: CandidateAllowList = RefreshingCandidateAllowList(PostgresCandidateRepository()),
    fn: suspend (ApplicationTestBuilder.() -> Unit),
) {
    sharedTestDatabase
    testApplication {
        environment {
            config = testApplicationConfig()
        }
        application {
            runBlocking { candidateAllowList.refresh() }
            configureTestApplication(candidateAllowList)
        }
        fn(this)
    }
}

fun Application.configureTestApplication(candidateAllowList: CandidateAllowList) {
    configureTestApplication(sharedTestAppConfig, candidateAllowList)
}

fun Application.configureTestApplication(
    appConfig: AppConfig,
    candidateAllowList: CandidateAllowList,
) {
    configureHTTP()
    configureSerialization()
    configureJwtAuthentication(appConfig)

    val versionsRepo = PostgresVersionRepository()
    val tagsRepo = PostgresTagRepository()
    val auditRepo = PostgresAuditRepository()
    val vendorRepo = PostgresVendorRepository()
    val tagService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo)
    val transactional = ExposedTransactional()
    val rateLimiter = RateLimiter(appConfig.rateLimitEnabled)
    val authService = AuthServiceImpl(vendorRepo, appConfig, rateLimiter)

    val candidateService = CandidateServiceImpl(PostgresCandidateRepository())

    val versionRequestValidator =
        VersionRequestValidator(appConfig.semverishEnabled, candidateAllowList)
    val versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional)

    configureRouting(
        versionService = versionService,
        tagService = tagService,
        healthRepo = PostgresHealthRepository(),
        authService = authService,
        vendorRepository = vendorRepo,
        appConfig = appConfig,
        versionRequestValidator = versionRequestValidator,
    )
    configureCandidateRouting(candidateService, candidateAllowList)
    configureJavaRouting(
        versionService = versionService,
        javaVersionService = JavaVersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
        javaVersionRequestValidator = JavaVersionRequestValidator(candidateAllowList),
    )
}
