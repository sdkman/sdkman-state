package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class VendorAuthorizationAcceptanceSpec :
    ShouldSpec({
        val authorizedVersion =
            Version(
                candidate = "java",
                version = "17.0.1",
                platform = Platform.LINUX_X64,
                url = "https://java-17.0.1",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some(),
            )

        should("allow vendor to POST version for authorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("java"))
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(authorizedVersion.toJsonString())
                            bearerAuth(vendorToken)
                        }

                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("allow vendor to DELETE version for authorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    insertVersions(authorizedVersion)
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("java"))
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","version":"17.0.1","platform":"LINUX_X64","distribution":"TEMURIN"}""",
                            )
                            bearerAuth(vendorToken)
                        }

                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("allow vendor to DELETE tag for authorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    // given: create version with tags
                    val versionWithTags = authorizedVersion.copy(tags = listOf("lts").some())
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("java"))

                    client.post("/versions") {
                        contentType(ContentType.Application.Json)
                        setBody(versionWithTags.toJsonString())
                        bearerAuth(vendorToken)
                    }

                    // when
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","tag":"lts","distribution":"TEMURIN","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(vendorToken)
                        }

                    // then
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("allow admin to POST version for any candidate") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(authorizedVersion.toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("return 403 when vendor POSTs version for unauthorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("kotlin"))
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(authorizedVersion.toJsonString())
                            bearerAuth(vendorToken)
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 when vendor DELETEs version for unauthorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    insertVersions(authorizedVersion)
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("kotlin"))
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","version":"17.0.1","platform":"LINUX_X64","distribution":"TEMURIN"}""",
                            )
                            bearerAuth(vendorToken)
                        }

                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        should("return 403 when vendor DELETEs tag for unauthorized candidate") {
            withCleanDatabase {
                withTestApplication {
                    // given: create version with tags using admin
                    val versionWithTags = authorizedVersion.copy(tags = listOf("lts").some())
                    client.post("/versions") {
                        contentType(ContentType.Application.Json)
                        setBody(versionWithTags.toJsonString())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: vendor tries to delete tag for unauthorized candidate
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("kotlin"))
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","tag":"lts","distribution":"TEMURIN","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(vendorToken)
                        }

                    // then
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    })
