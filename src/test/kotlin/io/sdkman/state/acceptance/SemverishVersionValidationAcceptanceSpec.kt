package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class SemverishVersionValidationAcceptanceSpec :
    ShouldSpec({

        should("accept conforming semverish version for opted-in candidate java") {
            // given: a valid semverish version for java (opted-in candidate)
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2-fx",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.2-fx.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the version is accepted
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("reject non-conforming version for opted-in candidate java") {
            // given: a non-conforming version for java (opted-in candidate)
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2.fx",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.2.fx.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the version is rejected with a validation error
                    response.status shouldBe HttpStatusCode.BadRequest
                    val errorResponse =
                        Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText())
                    errorResponse.failures.any { it.field == "version" } shouldBe true
                }
            }
        }

        should("accept non-conforming version for non-opted-in candidate scala") {
            // given: a non-conforming version for scala (not opted-in)
            val requestBody =
                """
                {
                    "candidate": "scala",
                    "version": "3.0.0-beta-1",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/scala-3.0.0-beta-1.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the version is accepted (no semverish validation)
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }
    })
