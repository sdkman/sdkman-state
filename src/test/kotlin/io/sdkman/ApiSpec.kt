package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.ktor.util.*
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.support.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class ApiSpec : ShouldSpec({

    should("GET all versions for a candidate") {
        val java17linuxArm64 = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.LINUX_ARM64,
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java21linuxArm64 = Version(
            candidate = "java",
            version = "21.0.1",
            vendor = "tem",
            platform = Platform.LINUX_ARM64,
            url = "https://java-21.0.1-tem",
            visible = true
        )
        val java17linuxX64 = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.LINUX_X64,
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java21linuxX64 = Version(
            candidate = "java",
            version = "21.0.1",
            vendor = "tem",
            platform = Platform.LINUX_X64,
            url = "https://java-21.0.1-tem",
            visible = true
        )

        withCleanDatabase {
            insertVersions(java17linuxArm64, java21linuxArm64, java17linuxX64, java21linuxX64)
            withTestApplication {
                client.get("/versions/java").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(
                        listOf(
                            java17linuxX64.toJson(),
                            java17linuxArm64.toJson(),
                            java21linuxX64.toJson(),
                            java21linuxArm64.toJson(),
                        )
                    )
                }
            }
        }
    }

    should("GET all versions for a universal candidate") {
        val java17universal = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.UNIVERSAL,
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java21universal = Version(
            candidate = "java",
            version = "21.0.1",
            vendor = "tem",
            platform = Platform.UNIVERSAL,
            url = "https://java-21.0.1-tem",
            visible = true
        )

        withCleanDatabase {
            insertVersions(java17universal, java21universal)
            withTestApplication {
                client.get("/versions/java/universal").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(
                        listOf(
                            java17universal.toJson(),
                            java21universal.toJson(),
                        )
                    )
                }
            }
        }
    }

    should("GET all versions for a multi-platform candidate") {
        val java17linuxArm64 = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.LINUX_ARM64,
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java21linuxArm64 = Version(
            candidate = "java",
            version = "21.0.1",
            vendor = "tem",
            platform = Platform.LINUX_ARM64,
            url = "https://java-21.0.1-tem",
            visible = true
        )
        val java17linuxX64 = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.LINUX_X64,
            url = "https://java-17.0.1-tem",
            visible = true
        )
        val java21linuxX64 = Version(
            candidate = "java",
            version = "21.0.1",
            vendor = "tem",
            platform = Platform.LINUX_X64,
            url = "https://java-21.0.1-tem",
            visible = true
        )

        withCleanDatabase {
            insertVersions(java17linuxArm64, java21linuxArm64, java17linuxX64, java21linuxX64)
            withTestApplication {
                client.get("/versions/java/linuxarm64").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(
                        listOf(
                            java17linuxArm64.toJson(),
                            java21linuxArm64.toJson(),
                        )
                    )
                }
                client.get("/versions/java/linuxx64").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(
                        listOf(
                            java17linuxX64.toJson(),
                            java21linuxX64.toJson(),
                        )
                    )
                }
            }
        }
    }

    should("POST a new version for a candidate, platform and vendor") {
        val version = Version(
            candidate = "java",
            version = "17.0.1",
            vendor = "tem",
            platform = Platform.MAC_X64,
            url = "https://java-17.0.1-tem",
            visible = true,
            md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e".some()
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("DELETE a version for a candidate, platform and vendor") {
        val candidate = "java"
        val version = "17.0.1"
        val vendor = "tem"
        val platform = Platform.MAC_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            vendor = vendor,
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    vendor = vendor,
                    platform = platform,
                    url = "https://java-17.0.1-tem",
                    visible = true,
                    md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e".some()
                )
            )
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(candidate, version, vendor, platform) shouldBe None
        }
    }
})

