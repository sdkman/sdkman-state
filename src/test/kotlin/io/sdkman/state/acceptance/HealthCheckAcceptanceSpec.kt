package io.sdkman.state.acceptance

import arrow.core.getOrElse
import arrow.core.none
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.primary.rest.dto.HealthCheckResponse
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
import io.sdkman.state.config.AppConfig
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.config.createHikariDataSource
import io.sdkman.state.support.sharedTestDatabase
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

@Tags("acceptance")
class HealthCheckAcceptanceSpec :
    ShouldSpec({

        should("return SUCCESS status when database is available") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/meta/health").apply {
                        status shouldBe HttpStatusCode.OK
                        contentType()
                            .toOption()
                            .map { it.withoutParameters() }
                            .getOrElse { error("no content type") } shouldBe ContentType.Application.Json

                        val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                        response.status shouldBe "SUCCESS"
                        response.message shouldBe none()
                    }
                }
            }
        }

        should("return FAILURE status when database is unavailable") {
            val appConfig = DefaultAppConfig(testApplicationConfig())
            val badAppConfig =
                object : AppConfig by appConfig {
                    override val databasePort: Int = 9999
                    override val databasePoolConnectionTimeoutMs: Long = 1_000L
                    override val databasePoolMaxSize: Int = 1
                    override val databasePoolMinIdle: Int = 0
                }
            val badDataSource = createHikariDataSource(badAppConfig)
            val badDatabase =
                Database.connect(
                    datasource = badDataSource,
                    databaseConfig =
                        DatabaseConfig {
                            defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
                        },
                )
            try {
                testApplication {
                    environment {
                        config = testApplicationConfig()
                    }
                    application {
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
                            versionRequestValidator = VersionRequestValidator(appConfig.semverishCandidates),
                        )
                    }

                    client.get("/meta/health").apply {
                        status shouldBe HttpStatusCode.ServiceUnavailable
                        contentType()
                            .toOption()
                            .map { it.withoutParameters() }
                            .getOrElse { error("no content type") } shouldBe ContentType.Application.Json

                        val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                        response.status shouldBe "FAILURE"
                        response.message
                            .map { it shouldContain "Database connection failed" }
                            .getOrElse { error("no message for failure") }
                    }
                }
            } finally {
                TransactionManager.closeAndUnregister(badDatabase)
                badDataSource.close()
                // Re-prime the shared default so subsequent specs use the working pool.
                sharedTestDatabase
            }
        }
    })
