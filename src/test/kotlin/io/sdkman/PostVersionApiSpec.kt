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
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.selectVersion
import io.sdkman.support.toJsonString
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class PostVersionApiSpec : ShouldSpec({

    should("POST a new version for a candidate, platform and distribution") {
        val version = Version(
            candidate = "java",
            version = "17.0.1",
            platform = Platform.MAC_X64,
            url = "https://java-17.0.1-tem",
            visible = true.some(),
            distribution = Distribution.TEMURIN.some(),
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
                distribution = version.distribution,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("POST a new version without distribution") {
        val version = Version(
            candidate = "maven",
            version = "3.9.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/maven-3.9.0.zip",
            visible = true.some(),
            distribution = None
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
                distribution = version.distribution,
                platform = version.platform
            ) shouldBe version.some()
        }
    }

    should("return 400 Bad Request when version contains distribution suffix") {
        val version = Version(
            candidate = "java",
            version = "17.0.1-tem",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.1.tar.gz",
            visible = true.some(),
            distribution = Distribution.TEMURIN.some()
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
                response.bodyAsText() shouldContain "Version '17.0.1-tem' should not contain distribution 'tem' suffix"
            }
        }
    }

    should("return 400 Bad Request when distribution value is invalid") {
        val requestBody = """
            {
                "candidate": "java",
                "version": "17.0.1",
                "platform": "LINUX_X64",
                "url": "https://example.com/java.tar.gz",
                "visible": true,
                "distribution": "INVALID_DISTRO"
            }
        """.trimIndent()

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Invalid request"
            }
        }
    }
})

