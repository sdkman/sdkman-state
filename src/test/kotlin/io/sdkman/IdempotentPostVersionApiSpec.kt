package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.selectVersion
import io.sdkman.support.toJsonString
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class IdempotentPostVersionApiSpec : ShouldSpec({

    should("POST be idempotent - same version posted twice should succeed with 201") {
        val version = Version(
            candidate = "java",
            version = "17.0.2",
            platform = Platform.LINUX_X64,
            url = "https://java-17.0.2-original",
            visible = true,
            vendor = "temurin".some(),
            md5sum = "original-hash".some()
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            withTestApplication {
                // First POST
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST (idempotent)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            // Verify version exists in database
            selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("POST overwrite existing version with different data") {
        val originalVersion = Version(
            candidate = "java",
            version = "17.0.3",
            platform = Platform.LINUX_X64,
            url = "https://java-17.0.3-original",
            visible = true,
            vendor = "temurin".some(),
            md5sum = "original-hash".some()
        )

        val updatedVersion = Version(
            candidate = "java",
            version = "17.0.3",
            platform = Platform.LINUX_X64,
            url = "https://java-17.0.3-updated",
            visible = false,
            vendor = "temurin".some(),
            sha256sum = "updated-hash".some()
        )

        withCleanDatabase {
            withTestApplication {
                // First POST
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(originalVersion.toJsonString())
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST with different data (overwrite)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(updatedVersion.toJsonString())
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            // Verify the updated version is stored
            selectVersion(
                candidate = updatedVersion.candidate,
                version = updatedVersion.version,
                vendor = updatedVersion.vendor,
                platform = updatedVersion.platform
            ) shouldBe updatedVersion.some()
        }
    }

    should("POST be idempotent for version without vendor") {
        val version = Version(
            candidate = "scala",
            version = "3.2.0",
            platform = Platform.UNIVERSAL,
            url = "https://scala-3.2.0",
            visible = true,
            vendor = None
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            withTestApplication {
                // First POST
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST (idempotent)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            // Verify version exists in database
            selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            ) shouldBe version.some()
        }
    }
})