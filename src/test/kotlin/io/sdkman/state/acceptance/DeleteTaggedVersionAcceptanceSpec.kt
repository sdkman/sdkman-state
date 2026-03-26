package io.sdkman.state.acceptance

import arrow.core.None
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
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
class DeleteTaggedVersionAcceptanceSpec :
    ShouldSpec({

        should("return 409 Conflict when deleting a version with multiple tags") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            val requestBody =
                UniqueVersion(
                    candidate = candidate,
                    version = version,
                    distribution = distribution.some(),
                    platform = platform,
                ).toJsonString()

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

                // when: attempting to delete the tagged version
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 409 Conflict with tag list
                    response.status shouldBe HttpStatusCode.Conflict
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("error").jsonPrimitive.content shouldBe "Conflict"
                    body.getValue("message").jsonPrimitive.content shouldBe
                        "Cannot delete version with active tags. Remove or reassign the following tags first."
                    body.getValue("tags").jsonArray.map { it.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                        listOf("latest", "27")
                }

                // and: the version is still in the database
                selectVersion(candidate, version, distribution.some(), platform).shouldBeSome()
            }
        }

        should("return 409 Conflict when deleting a version with a single tag") {
            val candidate = "java"
            val version = "27.0.1"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            val requestBody =
                UniqueVersion(
                    candidate = candidate,
                    version = version,
                    distribution = distribution.some(),
                    platform = platform,
                ).toJsonString()

            withCleanDatabase {
                // given: a version with a single tag
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.1-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )
                insertTag(candidate, "latest", distribution.some(), platform, versionId)

                // when: attempting to delete the tagged version
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 409 Conflict
                    response.status shouldBe HttpStatusCode.Conflict
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.getValue("tags").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("latest")
                }
            }
        }

        should("return 204 No Content when deleting an untagged version") {
            val candidate = "java"
            val version = "26.0.3"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            val requestBody =
                UniqueVersion(
                    candidate = candidate,
                    version = version,
                    distribution = distribution.some(),
                    platform = platform,
                ).toJsonString()

            withCleanDatabase {
                // given: a version with no tags
                insertVersions(
                    Version(
                        candidate = candidate,
                        version = version,
                        platform = platform,
                        url = "https://java-26.0.3-tem",
                        visible = true.some(),
                        distribution = distribution.some(),
                    ),
                )

                // when: deleting the untagged version
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version is removed from the database
                selectVersion(candidate, version, distribution.some(), platform) shouldBe None
            }
        }

        should("return 204 No Content when deleting a version after its tags are removed") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            val requestBody =
                UniqueVersion(
                    candidate = candidate,
                    version = version,
                    distribution = distribution.some(),
                    platform = platform,
                ).toJsonString()

            withCleanDatabase {
                // given: a version that had tags which were then removed (by moving to a new version)
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
                // no tags inserted — simulates tags already moved to another version

                // when: deleting the now-untagged version
                withTestApplication {
                    val response =
                        client.delete("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version is removed from the database
                selectVersion(candidate, version, distribution.some(), platform) shouldBe None
            }
        }
    })
