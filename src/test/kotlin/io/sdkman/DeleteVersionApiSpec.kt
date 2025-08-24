package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.support.*

// testuser:password123 base64 encoded
private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class DeleteVersionApiSpec : ShouldSpec({

    should("DELETE a version for a candidate, platform and vendor") {
        val candidate = "java"
        val version = "17.0.1"
        val vendor = "temurin"
        val platform = Platform.MAC_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            vendor = vendor.some(),
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://java-17.0.1-tem",
                    visible = true,
                    vendor = vendor.some(),
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
            selectVersion(candidate, version, vendor.some(), platform) shouldBe None
        }
    }

    should("DELETE a version for a candidate with platform and NO vendor") {
        val candidate = "scala"
        val version = "3.1.2"
        val platform = Platform.LINUX_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            vendor = None,
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://scala-3.1.2-linux-x64",
                    visible = true,
                    vendor = None
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
        val invalidRequestBody = """{"candidate":"","version":"1.0.0","vendor":null,"platform":"UNIVERSAL"}"""

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
            vendor = "temurin".some(),
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
            vendor = None,
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
            vendor = None,
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

    should("return BAD_REQUEST for UniqueVersion with vendor suffix in version") {
        val invalidRequestBody = UniqueVersion(
            candidate = "java",
            version = "17.0.1-temurin",
            vendor = "temurin".some(),
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
            vendor = None,
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
        val vendor = "jetbrains"
        val platform = Platform.LINUX_X64

        val requestBody = UniqueVersion(
            candidate = candidate,
            version = version,
            vendor = vendor.some(),
            platform = platform,
        ).toJsonString()

        withCleanDatabase {
            insertVersions(
                Version(
                    candidate = candidate,
                    version = version,
                    platform = platform,
                    url = "https://kotlin-1.9.0-linux",
                    visible = true,
                    vendor = vendor.some(),
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
            selectVersion(candidate, version, vendor.some(), platform) shouldBe None
        }
    }

    should("return 404 NOT_FOUND when attempting to delete non-existent version with vendor") {
        val requestBody = UniqueVersion(
            candidate = "gradle",
            version = "8.0.0",
            vendor = "gradle-inc".some(),
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
})