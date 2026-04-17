package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class IdempotentPostVersionAcceptanceSpec :
    ShouldSpec({

        should("POST be idempotent - same version posted twice should succeed with 204") {
            val version =
                Version(
                    candidate = "java",
                    version = "17.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.2-original",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    md5sum = "abc123def456abc123def456abc123de".some(),
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    // First POST
                    val response1 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response1.status shouldBe HttpStatusCode.NoContent

                    // Second POST (idempotent)
                    val response2 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response2.status shouldBe HttpStatusCode.NoContent
                }
                // Verify version exists in database
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }

        should("POST overwrite existing version with different data") {
            val originalVersion =
                Version(
                    candidate = "java",
                    version = "17.0.3",
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.3-original",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    md5sum = "abc123def456abc123def456abc123de".some(),
                )

            val updatedVersion =
                Version(
                    candidate = "java",
                    version = "17.0.3",
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.3-updated",
                    visible = false.some(),
                    distribution = Distribution.TEMURIN.some(),
                    sha256sum = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1".some(),
                )

            withCleanDatabase {
                withTestApplication {
                    // First POST
                    val response1 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(originalVersion.toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response1.status shouldBe HttpStatusCode.NoContent

                    // Second POST with different data (overwrite)
                    val response2 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(updatedVersion.toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response2.status shouldBe HttpStatusCode.NoContent
                }
                // Verify the updated version is stored
                selectVersion(
                    candidate = updatedVersion.candidate,
                    version = updatedVersion.version,
                    distribution = updatedVersion.distribution,
                    platform = updatedVersion.platform,
                ) shouldBe updatedVersion.some()
            }
        }

        should("POST be idempotent for version without distribution") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.2.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://scala-3.2.0",
                    visible = true.some(),
                    distribution = none(),
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    // First POST
                    val response1 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response1.status shouldBe HttpStatusCode.NoContent

                    // Second POST (idempotent)
                    val response2 =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response2.status shouldBe HttpStatusCode.NoContent
                }
                // Verify version exists in database
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }
    })
