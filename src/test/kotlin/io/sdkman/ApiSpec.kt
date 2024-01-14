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
                client.get("/versions/java").apply {
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

    should("POST a new version for a candidate, platform and vendor") {
        val expected = CandidateVersion(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = "MACOS_64",
            url = "https://java-17.0.1-tem",
            visible = true,
            md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e"
        )
        val request = expected.toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                    // testuser:password123 base64 encoded
                    header("Authorization", "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM=")
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(
                candidate = expected.candidate,
                version = expected.version,
                vendor = expected.vendor,
                platform = expected.platform
            ) shouldBe expected
        }
    }
})

