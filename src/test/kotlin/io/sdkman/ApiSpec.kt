package io.sdkman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.support.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class ApiSpec : ShouldSpec({

    should("GET all versions for a candidate") {
        val java17linuxArm64 = CandidateVersion(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = "LINUX_ARM64",
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java17linuxX64 = CandidateVersion(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = "LINUX_X64",
            url = "https://java-17.0.1-tem-",
            visible = true
        )

        withCleanDatabase {
            insertVersions(java17linuxArm64, java17linuxX64)
            withTestApplication {
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
    }
})

