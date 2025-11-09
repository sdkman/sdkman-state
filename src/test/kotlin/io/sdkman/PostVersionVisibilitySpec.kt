package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
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

class PostVersionVisibilitySpec : ShouldSpec({

    should("POST new version with explicit visible=true stores version with visible=true") {
        val version = Version(
            candidate = "java",
            version = "17.0.1",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.1.tar.gz",
            visible = true.some(),
            vendor = "temurin".some()
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

    should("POST new version with explicit visible=false stores version with visible=false") {
        val version = Version(
            candidate = "java",
            version = "17.0.2",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.2.tar.gz",
            visible = false.some(),
            vendor = "temurin".some()
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

    should("POST new version with visible omitted defaults to visible=true") {
        val version = Version(
            candidate = "scala",
            version = "3.1.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/scala-3.1.0.tar.gz",
            visible = None
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
            val storedVersion = selectVersion(
                candidate = version.candidate,
                version = version.version,
                vendor = version.vendor,
                platform = version.platform
            )
            storedVersion shouldBe version.copy(visible = true.some()).some()
        }
    }

    should("Update existing version with visible=false sets visible=false") {
        val initialVersion = Version(
            candidate = "kotlin",
            version = "1.9.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/kotlin-1.9.0.zip",
            visible = true.some()
        )
        val requestBody1 = initialVersion.toJsonString()

        withCleanDatabase {
            withTestApplication {
                // First POST creates with visible=true
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody1)
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST updates with visible=false
                val updatedVersion = initialVersion.copy(visible = false.some())
                val requestBody2 = updatedVersion.toJsonString()
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody2)
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(
                candidate = initialVersion.candidate,
                version = initialVersion.version,
                vendor = initialVersion.vendor,
                platform = initialVersion.platform
            ) shouldBe initialVersion.copy(visible = false.some()).some()
        }
    }

    should("Update existing version with visible omitted defaults to visible=true") {
        val initialVersion = Version(
            candidate = "groovy",
            version = "4.0.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/groovy-4.0.0.zip",
            visible = false.some()
        )
        val requestBody1 = initialVersion.toJsonString()

        withCleanDatabase {
            withTestApplication {
                // First POST creates with visible=false
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody1)
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST updates with visible omitted (should default to true)
                val updatedVersion = initialVersion.copy(visible = None)
                val requestBody2 = updatedVersion.toJsonString()
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody2)
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(
                candidate = initialVersion.candidate,
                version = initialVersion.version,
                vendor = initialVersion.vendor,
                platform = initialVersion.platform
            ) shouldBe initialVersion.copy(visible = true.some()).some()
        }
    }

    should("Update existing version from visible=false to visible=true using explicit Some(true)") {
        val initialVersion = Version(
            candidate = "gradle",
            version = "8.0.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/gradle-8.0.0.zip",
            visible = false.some()
        )
        val requestBody1 = initialVersion.toJsonString()

        withCleanDatabase {
            withTestApplication {
                // First POST creates with visible=false
                val response1 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody1)
                    header(Authorization, BasicAuthHeader)
                }
                response1.status shouldBe HttpStatusCode.NoContent

                // Second POST explicitly sets visible=true
                val updatedVersion = initialVersion.copy(visible = true.some())
                val requestBody2 = updatedVersion.toJsonString()
                val response2 = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody2)
                    header(Authorization, BasicAuthHeader)
                }
                response2.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(
                candidate = initialVersion.candidate,
                version = initialVersion.version,
                vendor = initialVersion.vendor,
                platform = initialVersion.platform
            ) shouldBe initialVersion.copy(visible = true.some()).some()
        }
    }
})
