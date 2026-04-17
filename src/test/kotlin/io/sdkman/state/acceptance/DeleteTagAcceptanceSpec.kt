package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.*
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.shouldBeSome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class DeleteTagAcceptanceSpec :
    ShouldSpec({

        // Happy path tests

        should("delete a tag and return 204 No Content") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            val requestBody =
                UniqueTag(
                    candidate = candidate,
                    tag = "latest",
                    distribution = distribution.some(),
                    platform = platform,
                ).toJsonString()

            withCleanDatabase {
                // given: a version with a tag
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)

                // when: deleting the tag
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the tag is gone
                selectTagNames(versionId) shouldBe emptyList()
            }
        }

        should("delete a tag while preserving other tags on the same version") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with multiple tags
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)
                insertTag(candidate, "27", distribution.some(), platform, versionId)
                insertTag(candidate, "27.0", distribution.some(), platform, versionId)

                // when: deleting one tag
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "27.0",
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // then: other tags remain
                selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest", "27")
            }
        }

        should("delete a tag while preserving the version itself") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with a tag
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)

                // when: deleting the tag
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "latest",
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // then: the version still exists
                selectVersion(candidate, version, distribution.some(), platform).shouldBeSome()
            }
        }

        should("delete all tags then successfully delete the version") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with tags
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)
                insertTag(candidate, "27", distribution.some(), platform, versionId)

                withTestApplication {
                    // step 1: delete "latest" tag
                    client
                        .delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "latest",
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.NoContent

                    // step 2: delete "27" tag
                    client
                        .delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "27",
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.NoContent

                    // step 3: delete the now-untagged version
                    client
                        .delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueVersion(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.NoContent
                }

                // then: both tags and version are gone
                selectTagNames(versionId) shouldBe emptyList()
                selectVersion(candidate, version, distribution.some(), platform) shouldBe none()
            }
        }

        should("delete a tag for a candidate without distribution") {
            val candidate = "gradle"
            val platform = Platform.UNIVERSAL

            withCleanDatabase {
                // given: a version without distribution, with a tag
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = "8.10",
                            platform = platform,
                            url = "https://gradle-8.10.zip",
                            visible = true.some(),
                            distribution = none(),
                        ),
                    )
                insertTag(candidate, "latest", none(), platform, versionId)

                // when: deleting the tag without distribution
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "latest",
                                    distribution = none(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // then: the tag is gone
                selectTagNames(versionId) shouldBe emptyList()
            }
        }

        // Unhappy path tests

        should("return 404 Not Found when deleting a non-existent tag") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = "java",
                                    tag = "nonexistent",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("error").jsonPrimitive.content shouldBe "Not Found"
                    body.getValue("message").jsonPrimitive.content shouldBe "Tag 'nonexistent' not found"
                }
            }
        }

        should("return 400 Bad Request when candidate is blank") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"","tag":"latest","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("error").jsonPrimitive.content shouldBe "Validation Error"
                }
            }
        }

        should("return 400 Bad Request when tag name is blank") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","tag":"","distribution":"TEMURIN","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("error").jsonPrimitive.content shouldBe "Validation Error"
                }
            }
        }

        should("return 400 Bad Request when distribution is invalid") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","tag":"latest","distribution":"INVALID_DISTRO","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 Bad Request when platform is invalid") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"java","tag":"latest","platform":"INVALID_PLATFORM"}""",
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 Bad Request with accumulated validation failures") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"candidate":"","tag":"","platform":"LINUX_X64"}""",
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("error").jsonPrimitive.content shouldBe "Validation Error"
                    val failures = body.getValue("failures").jsonArray
                    failures
                        .map {
                            it.jsonObject
                                .getValue("field")
                                .jsonPrimitive.content
                        }.shouldContainExactlyInAnyOrder(listOf("candidate", "tag"))
                }
            }
        }

        should("return 401 Unauthorized when deleting without authentication") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = "java",
                                    tag = "latest",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                ).toJsonString(),
                            )
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 400 Bad Request for malformed JSON body") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"java","tag":}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        // Audit test

        should("create audit record when tag is deleted") {
            val candidate = "java"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with a tag
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = "27.0.2",
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)

                // when: deleting the tag
                withTestApplication {
                    client
                        .delete("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                UniqueTag(
                                    candidate = candidate,
                                    tag = "latest",
                                    distribution = distribution.some(),
                                    platform = platform,
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }.status shouldBe HttpStatusCode.NoContent
                }

                // then: audit record is created
                val auditRecords = selectAuditRecords()
                auditRecords.size shouldBe 1
                val record = auditRecords.first()
                record.email shouldBe "admin@sdkman.io"
                record.operation shouldBe io.sdkman.state.domain.model.AuditOperation.DELETE

                val auditData = Json.parseToJsonElement(record.versionData).jsonObject
                auditData.getValue("candidate").jsonPrimitive.content shouldBe candidate
                auditData.getValue("tag").jsonPrimitive.content shouldBe "latest"
                auditData.getValue("platform").jsonPrimitive.content shouldBe platform.name
            }
        }
    })
