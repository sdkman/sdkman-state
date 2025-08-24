package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.selectVersion
import io.sdkman.support.toJsonString
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class PostVersionApiSpec : ShouldSpec({

    should("POST a new version for a candidate, platform and vendor") {
        val version = Version(
            candidate = "java",
            version = "17.0.1",
            platform = Platform.MAC_X64,
            url = "https://java-17.0.1-tem",
            visible = true,
            vendor = "temurin".some(),
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
                response.status shouldBe HttpStatusCode.Created
            }
            selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("POST a new version without vendor") {
        val version = Version(
            candidate = "maven",
            version = "3.9.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/maven-3.9.0.zip",
            visible = true,
            vendor = None
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.Created
            }
            selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("return 400 Bad Request when version contains vendor suffix") {
        val version = Version(
            candidate = "java",
            version = "17.0.1-tem",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.1.tar.gz",
            visible = true,
            vendor = "tem".some()
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Version '17.0.1-tem' should not contain vendor 'tem' suffix"
            }
        }
    }

    //TODO: Move this into a new IdempotentVersionPostApiSpec
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
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.Created

                // Second POST (idempotent)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.Created
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

    //TODO: Move this into a new IdempotentVersionPostApiSpec
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
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.Created

                // Second POST with different data (overwrite)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(updatedVersion.toJsonString())
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.Created
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

    //TODO: Move this into a new IdempotentVersionPostApiSpec
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
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.Created

                // Second POST (idempotent)
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.Created
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

