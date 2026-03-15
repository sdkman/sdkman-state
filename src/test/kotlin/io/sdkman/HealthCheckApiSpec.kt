package io.sdkman

import arrow.core.None
import arrow.core.getOrElse
import arrow.core.toOption
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.domain.HealthStatus
import io.sdkman.plugins.HealthCheckResponse
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.AuditRepositoryImpl
import io.sdkman.repos.HealthRepositoryImpl
import io.sdkman.repos.TagsRepositoryImpl
import io.sdkman.repos.VersionsRepository
import io.sdkman.service.TagServiceImpl
import io.sdkman.service.VersionServiceImpl
import io.sdkman.support.withCleanDatabase
import kotlinx.serialization.json.Json

class HealthCheckApiSpec :
    ShouldSpec({

        should("return SUCCESS status when database is available") {
            withCleanDatabase {
                testApplication {
                    environment {
                        config = ApplicationConfig("application.conf")
                    }
                    application {
                        val dbConfig = configureAppConfig(environment).databaseConfig
                        configureDatabase(dbConfig)

                        val versionsRepo = VersionsRepository()
                        val tagsRepo = TagsRepositoryImpl()
                        val auditRepo = AuditRepositoryImpl()

                        configureRouting(
                            versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
                            tagService = TagServiceImpl(tagsRepo, auditRepo),
                            healthRepo = HealthRepositoryImpl(),
                        )
                    }

                    client.get("/meta/health").apply {
                        status shouldBe HttpStatusCode.OK
                        contentType()
                            .toOption()
                            .map { it.withoutParameters() }
                            .getOrElse { error("no content type") } shouldBe ContentType.Application.Json

                        val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                        response.status shouldBe HealthStatus.SUCCESS
                        response.message shouldBe None
                    }
                }
            }
        }

        should("return FAILURE status when database is unavailable") {
            testApplication {
                environment {
                    config = ApplicationConfig("application.conf")
                }
                application {
                    val dbConfig = configureAppConfig(environment).databaseConfig
                    val badDbConfig = dbConfig.copy(port = 9999)
                    configureDatabase(badDbConfig)

                    val versionsRepo = VersionsRepository()
                    val tagsRepo = TagsRepositoryImpl()
                    val auditRepo = AuditRepositoryImpl()

                    configureRouting(
                        versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
                        tagService = TagServiceImpl(tagsRepo, auditRepo),
                        healthRepo = HealthRepositoryImpl(),
                    )
                }

                client.get("/meta/health").apply {
                    status shouldBe HttpStatusCode.ServiceUnavailable
                    contentType()
                        .toOption()
                        .map { it.withoutParameters() }
                        .getOrElse { error("no content type") } shouldBe ContentType.Application.Json

                    val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                    response.status shouldBe HealthStatus.FAILURE
                    response.message
                        .map { it shouldContain "Database connection failed" }
                        .getOrElse { error("no message for failure") }
                }
            }
        }
    })
