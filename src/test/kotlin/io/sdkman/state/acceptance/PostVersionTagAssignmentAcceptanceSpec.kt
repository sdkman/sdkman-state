package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.*
import io.sdkman.state.support.JwtTestSupport

@Tags("acceptance")
class PostVersionTagAssignmentAcceptanceSpec :
    ShouldSpec({

        should("assign a new tag to an existing version and return 204 No Content") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with no tags
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

                // when: assigning a tag to the version
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version carries the assigned tag
                selectTagNames(versionId) shouldContain "latest"
            }
        }

        should("move a tag from another version, leaving the source version's other tags intact") {
            val candidate = "java"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: version A holds "latest" and "27"; version B exists untagged
                val versionIdA =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = "27.0.1",
                            platform = platform,
                            url = "https://java-27.0.1-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                val versionIdB =
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
                insertTag(candidate, "latest", distribution.some(), platform, versionIdA)
                insertTag(candidate, "27", distribution.some(), platform, versionIdA)

                // when: assigning "latest" to version B
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = "27.0.2",
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: B gains "latest"; A loses "latest" but keeps "27"
                selectTagNames(versionIdB) shouldContain "latest"
                selectTagNames(versionIdA) shouldNotContain "latest"
                selectTagNames(versionIdA) shouldContainExactlyInAnyOrder listOf("27")
            }
        }

        should("preserve the target version's existing tags when assigning a new one") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version already tagged "27"
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
                insertTag(candidate, "27", distribution.some(), platform, versionId)

                // when: assigning a new tag "latest" to the version
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version carries both the existing and the newly assigned tag
                selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("27", "latest")
            }
        }

        should("be an idempotent 204 no-op when re-assigning a tag the version already holds") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version already tagged "latest"
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

                // when: re-assigning the tag it already holds
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content (idempotent success)
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the tag set is unchanged
                selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest")
            }
        }

        should("assign a tag to a candidate without a distribution and return 204 No Content") {
            val candidate = "gradle"
            val version = "8.12"
            val platform = Platform.UNIVERSAL

            withCleanDatabase {
                // given: a distribution-less version with no tags
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://gradle-8.12.zip",
                            visible = true.some(),
                            distribution = none(),
                        ),
                    )

                // when: assigning a tag without a distribution
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = none(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version carries the assigned tag within the NULL-distribution scope
                selectTagNames(versionId) shouldContain "latest"
            }
        }

        should("return 404 Not Found with an empty body when the target version does not exist") {
            val candidate = "java"
            val version = "99.0.0"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: no version exists for the requested coordinates

                // when: assigning a tag to a non-existent version
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 404 Not Found with no body
                    response.status shouldBe HttpStatusCode.NotFound
                    response.bodyAsText() shouldBe ""
                }

                // and: no version was created for the requested coordinates
                selectVersion(candidate, version, distribution.some(), platform) shouldBe none()
            }
        }

        should("return 400 Bad Request when the tag format is invalid") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: the target version exists, so only the tag format is at fault
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

                // when: assigning a tag whose format breaks the tag rules
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "-bad-",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 400 Bad Request naming the offending field and rule
                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = response.bodyAsText()
                    body shouldContain "Validation Error"
                    body shouldContain "tag"
                    body shouldContain "must start and end with an alphanumeric character"
                }
            }
        }

        should("return 400 Bad Request accumulating blank candidate, blank version, and invalid tag failures") {
            withCleanDatabase {
                // when: posting an assignment that violates three rules at once
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = "",
                                    version = "",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                    tag = "-bad-",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 400 with all three failures reported together in one response
                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = response.bodyAsText()
                    body shouldContain "Validation Error"
                    body shouldContain "candidate cannot be empty"
                    body shouldContain "version cannot be empty"
                    body shouldContain "must start and end with an alphanumeric character"
                }
            }
        }

        should("return 400 Bad Request when the platform is not a valid enum value") {
            // a raw body is needed: the typed DTO cannot represent an invalid Platform
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "NOT_A_PLATFORM",
                    "tag": "latest"
                }
                """.trimIndent()

            withCleanDatabase {
                // when: posting an assignment with an unknown platform
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 400 — the bad enum is rejected at deserialization
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldContain "Invalid request"
                }
            }
        }

        should("return 400 Bad Request when the distribution is not a valid enum value") {
            // a raw body is needed: the typed DTO cannot represent an invalid Distribution
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "NOT_A_DISTRIBUTION",
                    "platform": "LINUX_X64",
                    "tag": "latest"
                }
                """.trimIndent()

            withCleanDatabase {
                // when: posting an assignment with an unknown distribution
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 400 — the bad enum is rejected at deserialization
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldContain "Invalid request"
                }
            }
        }

        should("return 401 Unauthorized when assigning a tag without authentication") {
            withCleanDatabase {
                // when: posting a well-formed assignment with no bearer token
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = "java",
                                    version = "27.0.2",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                        }

                    // then: 401 — authentication is required before any processing
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
