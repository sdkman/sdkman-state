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
private const val BASIC_AUTH_HEADER = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class PostVersionApiSpec :
    ShouldSpec({

        should("POST a new version for a candidate, platform and distribution") {
            val version =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    platform = Platform.MAC_X64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e".some(),
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }

        should("POST a new version without distribution") {
            val version =
                Version(
                    candidate = "maven",
                    version = "3.9.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/maven-3.9.0.zip",
                    visible = true.some(),
                    distribution = None,
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }

        should("accept version with suffix like -RC1") {
            val version =
                Version(
                    candidate = "kotlin",
                    version = "1.9.0-RC1",
                    platform = Platform.UNIVERSAL,
                    url = "https://github.com/JetBrains/kotlin/releases/download/1.9.0-RC1/kotlin.zip",
                    visible = true.some(),
                    distribution = None,
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }

        should("return 400 Bad Request when distribution value is invalid") {
            val requestBody =
                """
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
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldContain "Validation failed"
                    response.bodyAsText() shouldContain "Distribution 'INVALID_DISTRO' is not valid"
                }
            }
        }

        should("return 400 Bad Request with accumulated errors when multiple fields are invalid") {
            val requestBody =
                """
                {
                    "candidate": "invalid-candidate",
                    "version": "",
                    "platform": "INVALID_PLATFORM",
                    "url": "http://not-https.com/file.zip"
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "Candidate 'invalid-candidate' is not valid"
                    responseBody shouldContain "version cannot be empty"
                    responseBody shouldContain "Platform 'INVALID_PLATFORM' is not valid"
                    responseBody shouldContain "must be a valid HTTPS URL"
                }
            }
        }

        should("return 400 Bad Request when required fields are missing") {
            val requestBody =
                """
                {
                    "visible": true
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "candidate cannot be empty"
                    responseBody shouldContain "version cannot be empty"
                    responseBody shouldContain "platform cannot be empty"
                    responseBody shouldContain "url cannot be empty"
                }
            }
        }
    })
