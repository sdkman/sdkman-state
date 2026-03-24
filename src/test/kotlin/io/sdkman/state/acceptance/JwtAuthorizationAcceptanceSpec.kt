package io.sdkman.state.acceptance

import arrow.core.None
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.adminToken
import io.sdkman.state.support.expiredToken
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.vendorToken
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JwtAuthorizationAcceptanceSpec :
    ShouldSpec({

        should("allow vendor to POST version for authorized candidate") {
            val version =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/java-17.0.1.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${vendorToken(listOf("java", "kotlin"))}")
                            setBody(version.toJsonString())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("allow vendor to DELETE version for authorized candidate") {
            val version =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/java-17.0.1.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                insertVersions(version)
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${vendorToken(listOf("java"))}")
                            setBody(
                                """{"candidate": "java", "version": "17.0.1", "platform": "UNIVERSAL"}""",
                            )
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("allow admin to POST version for any candidate") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.0.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/scala-3.0.0.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody(version.toJsonString())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("return 401 for POST versions without token") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate": "java", "version": "17.0.1", "platform": "UNIVERSAL", "url": "https://example.com/java.zip"}""",
                            )
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for POST versions with expired token") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${expiredToken()}")
                            setBody(
                                """{"candidate": "java", "version": "17.0.1", "platform": "UNIVERSAL", "url": "https://example.com/java.zip"}""",
                            )
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 403 for vendor POST version with unauthorized candidate") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.0.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/scala-3.0.0.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${vendorToken(listOf("java"))}")
                            setBody(version.toJsonString())
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 for vendor DELETE version with unauthorized candidate") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.0.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/scala-3.0.0.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                insertVersions(version)
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${vendorToken(listOf("java"))}")
                            setBody(
                                """{"candidate": "scala", "version": "3.0.0", "platform": "UNIVERSAL"}""",
                            )
                        }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
