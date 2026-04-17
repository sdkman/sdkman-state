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
import io.sdkman.state.config.AppConfig
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureDatabase
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase
import kotlinx.serialization.json.Json

@Tags("acceptance")
class HealthCheckAcceptanceSpec :
    ShouldSpec({

        should("return SUCCESS status when database is available") {
            withCleanDatabase {
                testApplication {
                    environment {
                        config = testApplicationConfig()
                    }
                    application {
                        val appConfig = DefaultAppConfig(environment.config)
                        configureDatabase(appConfig)
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
            testApplication {
                environment {
                    config = testApplicationConfig()
                }
                application {
                    val appConfig = DefaultAppConfig(environment.config)
                    val badAppConfig =
                        object : AppConfig by appConfig {
                            override val databasePort: Int = 9999
                        }
                    configureDatabase(badAppConfig)
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
        }
    })
