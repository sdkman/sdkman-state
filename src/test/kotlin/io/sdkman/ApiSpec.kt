package io.sdkman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.*
import io.sdkman.repos.CandidateVersionsRepository
import io.sdkman.support.*
import io.sdkman.support.CandidateVersion

class ApiSpec : ShouldSpec({

    should("GET all versions for a candidate") {
        testApplication {
            application {
                val dbConfig = configureAppConfig(environment).databaseConfig
                configureDatabaseMigration(dbConfig)
                configureDatabase(dbConfig)
                configureRouting(CandidateVersionsRepository())
            }

            val java17linuxArm64 = CandidateVersion(
                candidate = "java",
                version = "17.0.1",
                platform = "LINUX_ARM64",
                vendor = "tem",
                url = "https://java-17.0.1-tem",
                visible = true
            )
            val java17linuxX64 = CandidateVersion(
                candidate = "java",
                version = "17.0.1",
                platform = "LINUX_X64",
                vendor = "tem",
                url = "https://java-17.0.1-tem-",
                visible = true
            )

            initialisePostgres()
            deleteVersions()
            insertVersions(java17linuxArm64, java17linuxX64)

            client.get("/candidates/java").apply {
                status shouldBe HttpStatusCode.OK
                Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(
                    listOf(
                        java17linuxArm64.toJson(),
                        java17linuxX64.toJson(),
                    )
                )
            }
        }
    }
})
