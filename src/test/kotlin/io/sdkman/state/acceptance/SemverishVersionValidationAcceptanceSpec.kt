package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class SemverishVersionValidationAcceptanceSpec :
    ShouldSpec({

        should("accept conforming semverish version for java") {
            // given: a valid semverish version for java
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "29.0.0.0+ea.10",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-29.0.0.0+ea.10.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("java")

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

        should("reject non-conforming version for java") {
            // given: a non-conforming version for java
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
                    registerCandidates("java")

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

        should("reject three-component version for java") {
            // given: a three-component core, valid before java required a fourth component
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "29.0.0+ea.10",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-29.0.0+ea.10.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("java")

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

        should("reject variant outside the fx or crac vocabulary for java") {
            // given: a four-component core carrying a variant other than fx or crac
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2.0-graal",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.2.0-graal.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("java")

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

        should("accept non-conforming version for candidate scala other than java") {
            // given: a non-conforming version for scala, which is not java
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
                    registerCandidates("scala")

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

        should("accept trailing elements for java") {
            // given: a java version 25.0.0.1.1.2
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "25.0.0.1.1.2",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.0.1.1.2.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("java")

                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the expected status is returned
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("reject ea-suffixed fourth component for java") {
            // given: a java version 25.0.2.0-ea.25
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2.0-ea.25",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.2.0-ea.25.tar.gz"
                }
                """.trimIndent()

            // when: posting the version
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("java")

                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the expected status is returned
                    response.status shouldBe HttpStatusCode.BadRequest
                    val errorResponse =
                        Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText())
                    errorResponse.failures.any { it.field == "version" } shouldBe true
                }
            }
        }
    })
