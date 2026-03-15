package io.sdkman.state

import arrow.core.None
import arrow.core.getOrElse
import arrow.core.toOption
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
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.configureAppConfig
import io.sdkman.state.config.configureBasicAuthentication
import io.sdkman.state.config.configureDatabase
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase
import kotlinx.serialization.json.Json

class HealthCheckApiSpec :
    ShouldSpec({

        should("return SUCCESS status when database is available") {
            withCleanDatabase {
                testApplication {
                    environment {
                        config = testApplicationConfig()
                    }
                    application {
                        val appConfig = configureAppConfig(environment)
                        configureDatabase(appConfig.databaseConfig)
                        configureSerialization()
                        configureBasicAuthentication(appConfig.apiAuthenticationConfig)

                        val versionsRepo = PostgresVersionRepository()
                        val tagsRepo = PostgresTagRepository()
                        val auditRepo = PostgresAuditRepository()

                        configureRouting(
                            versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
                            tagService = TagServiceImpl(tagsRepo, auditRepo),
                            healthRepo = PostgresHealthRepository(),
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
                        response.message shouldBe None
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
                    val appConfig = configureAppConfig(environment)
                    val badDbConfig = appConfig.databaseConfig.copy(port = 9999)
                    configureDatabase(badDbConfig)
                    configureSerialization()
                    configureBasicAuthentication(appConfig.apiAuthenticationConfig)

                    val versionsRepo = PostgresVersionRepository()
                    val tagsRepo = PostgresTagRepository()
                    val auditRepo = PostgresAuditRepository()

                    configureRouting(
                        versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
                        tagService = TagServiceImpl(tagsRepo, auditRepo),
                        healthRepo = PostgresHealthRepository(),
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
