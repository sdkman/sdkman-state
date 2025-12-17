package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.support.*

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class DeleteVersionApiSpec : ShouldSpec({

    should("DELETE a version for a candidate, platform and distribution") {
        val candidate = "java"
        val version = "17.0.1"
        val distribution = Distribution.TEMURIN
        val platform = Platform.MAC_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            distribution = distribution.some(),
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                    distribution = distribution.some(),
                    md5sum = "3bc0c1d7b4805831680ee5a8690ebb6e".some()
                )
            )
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(candidate, version, distribution.some(), platform) shouldBe None
        }
    }

    should("DELETE a version for a candidate with platform and NO distribution") {
        val candidate = "scala"
        val version = "3.1.2"
        val platform = Platform.LINUX_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            distribution =None,
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://scala-3.1.2-linux-x64",
                    visible = true.some(),
                    distribution =None
                )
            )
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(candidate, version, None, platform) shouldBe None
        }
    }

    should("return BAD_REQUEST for UniqueVersion validation failure") {
        val invalidRequestBody = """{"candidate":"","version":"1.0.0","distribution":null,"platform":"UNIVERSAL"}"""

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(invalidRequestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    should("return UNAUTHORIZED when deleting without authentication") {
        val requestBody = UniqueVersion(
            candidate = "java",
            version = "17.0.1",
            distribution = Distribution.TEMURIN.some(),
            platform = Platform.MAC_X64
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    }

    should("return BAD_REQUEST for UniqueVersion with empty candidate field") {
        val invalidRequestBody = UniqueVersion(
            candidate = "",
            version = "1.0.0",
            distribution =None,
            platform = Platform.UNIVERSAL
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(invalidRequestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    should("return BAD_REQUEST for UniqueVersion with empty version field") {
        val invalidRequestBody = UniqueVersion(
            candidate = "java",
            version = "",
            distribution =None,
            platform = Platform.UNIVERSAL
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(invalidRequestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    should("return BAD_REQUEST for UniqueVersion with distribution suffix in version") {
        val invalidRequestBody = UniqueVersion(
            candidate = "java",
            version = "17.0.1-temurin",
            distribution = Distribution.TEMURIN.some(),
            platform = Platform.MAC_X64
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(invalidRequestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    should("return NOT_FOUND when attempting to delete non-existent version") {
        val requestBody = UniqueVersion(
            candidate = "nonexistent",
            version = "1.0.0",
            distribution =None,
            platform = Platform.UNIVERSAL
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    should("return BAD_REQUEST for malformed JSON in delete request") {
        val malformedJson = """{"candidate":"java","version":}"""

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(malformedJson)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    should("return 204 NO_CONTENT for successful deletion of existing version") {
        val candidate = "kotlin"
        val version = "1.9.0"
        val distribution = Distribution.JETBRAINS
        val platform = Platform.LINUX_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            distribution = distribution.some(),
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://kotlin-1.9.0-linux",
                    visible = true.some(),
                    distribution = distribution.some(),
                    sha256sum = "kotlin-hash".some()
                )
            )
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }
            selectVersion(candidate, version, distribution.some(), platform) shouldBe None
        }
    }

    should("return 404 NOT_FOUND when attempting to delete non-existent version with distribution") {
        val requestBody = UniqueVersion(
            candidate = "gradle",
            version = "8.0.0",
            distribution = Distribution.GRAALVM.some(),
            platform = Platform.UNIVERSAL
        ).toJsonString()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    }

    should("return 400 Bad Request when distribution value is invalid") {
        val requestBody = """
            {
                "candidate": "java",
                "version": "17.0.1",
                "distribution": "INVALID_DISTRO",
                "platform": "LINUX_X64"
            }
        """.trimIndent()

        withCleanDatabase {
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(HttpHeaders.Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldContain "Invalid request"
            }
        }
    }
})