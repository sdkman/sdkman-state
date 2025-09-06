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
import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.domain.HealthStatus
import io.sdkman.plugins.HealthCheckResponse
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.VersionsRepository
import io.sdkman.support.withCleanDatabase
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.fail

class HealthCheckApiSpec : ShouldSpec({

    should("return SUCCESS status when database is available") {
        withCleanDatabase {
            testApplication {
                application {
                    val dbConfig = configureAppConfig(environment).databaseConfig
                    configureDatabase(dbConfig)
                    configureRouting(VersionsRepository())
                }

                client.get("/meta/health").apply {
                    status shouldBe HttpStatusCode.OK
                    contentType()?.withoutParameters() shouldBe ContentType.Application.Json

                    val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                    response.status shouldBe HealthStatus.SUCCESS
                    response.message.toOption() shouldBe None
                }
            }
        }
    }

    should("return FAILURE status when database is unavailable") {
        // This test will use a mock repository to simulate database failure
        testApplication {
            application {
                val dbConfig = configureAppConfig(environment).databaseConfig
                val badDbConfig = dbConfig.copy(port = 9999)
                configureDatabase(badDbConfig)
                configureRouting(VersionsRepository())
            }

            client.get("/meta/health").apply {
                status shouldBe HttpStatusCode.ServiceUnavailable
                contentType().toOption()
                    .map { it.withoutParameters() }
                    .getOrElse { fail { "no content type" } } shouldBe ContentType.Application.Json

                val response = Json.decodeFromString<HealthCheckResponse>(bodyAsText())
                response.status shouldBe HealthStatus.FAILURE
                response.message.toOption()
                    .map { it shouldContain "Database connection failed" }
                    .getOrElse { fail("no message for failure") }
            }
        }
    }
})