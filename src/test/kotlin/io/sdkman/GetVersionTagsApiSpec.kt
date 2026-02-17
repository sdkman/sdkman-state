package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.insertTag
import io.sdkman.support.insertVersionWithId
import io.sdkman.support.toJson
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class GetVersionTagsApiSpec :
    ShouldSpec({

        should("GET version list with tags on each version") {
            // given: versions with tags
            val java2701 =
                Version(
                    candidate = "java",
                    version = "27.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-27.0.1-temurin",
                    visible = true.some(),
                )
            val java2702 =
                Version(
                    candidate = "java",
                    version = "27.0.2",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-27.0.2-temurin",
                    visible = true.some(),
                )

            withCleanDatabase {
                val id1 = insertVersionWithId(java2701)
                val id2 = insertVersionWithId(java2702)
                insertTag("java", "27.0", Distribution.TEMURIN.some(), Platform.LINUX_X64, id1)
                insertTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64, id2)
                insertTag("java", "27", Distribution.TEMURIN.some(), Platform.LINUX_X64, id2)

                withTestApplication {
                    // when: GET all versions
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "linuxx64") }
                        }.apply {
                            // then: each version includes correct tags
                            status shouldBe HttpStatusCode.OK
                            val expected =
                                JsonArray(
                                    listOf(
                                        java2701.copy(tags = listOf("27.0").some()).toJson(),
                                        java2702.copy(tags = listOf("latest", "27").some()).toJson(),
                                    ),
                                )
                            Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe expected
                        }
                }
            }
        }

        should("GET version list with mixed tags — tagged and untagged versions") {
            // given: some versions with tags and some without
            val java2701 =
                Version(
                    candidate = "java",
                    version = "27.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-27.0.1-temurin",
                    visible = true.some(),
                )
            val java2605 =
                Version(
                    candidate = "java",
                    version = "26.0.5",
                    distribution = Distribution.CORRETTO.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-26.0.5-corretto",
                    visible = true.some(),
                )

            withCleanDatabase {
                val id1 = insertVersionWithId(java2701)
                insertVersionWithId(java2605)
                insertTag("java", "27.0", Distribution.TEMURIN.some(), Platform.LINUX_X64, id1)

                withTestApplication {
                    // when: GET all versions
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "linuxx64") }
                        }.apply {
                            // then: tagged version has tags, untagged has empty array
                            status shouldBe HttpStatusCode.OK
                            val expected =
                                JsonArray(
                                    listOf(
                                        java2605.copy(tags = emptyList<String>().some()).toJson(),
                                        java2701.copy(tags = listOf("27.0").some()).toJson(),
                                    ),
                                )
                            Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe expected
                        }
                }
            }
        }

        should("GET single version with tags") {
            // given: a version with tags
            val java2702 =
                Version(
                    candidate = "java",
                    version = "27.0.2",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-27.0.2-temurin",
                    visible = true.some(),
                )

            withCleanDatabase {
                val id = insertVersionWithId(java2702)
                insertTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)
                insertTag("java", "27", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)
                insertTag("java", "27.0", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)

                withTestApplication {
                    // when: GET single version
                    client.get("/versions/java/27.0.2?platform=linuxx64&distribution=TEMURIN").apply {
                        // then: response includes all tags
                        status shouldBe HttpStatusCode.OK
                        val expected = java2702.copy(tags = listOf("latest", "27", "27.0").some()).toJson()
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe expected
                    }
                }
            }
        }

        should("GET single version without tags returns empty tags array") {
            // given: a version with no tags
            val gradle810 =
                Version(
                    candidate = "gradle",
                    version = "8.10",
                    platform = Platform.UNIVERSAL,
                    url = "https://gradle-8.10.zip",
                    visible = true.some(),
                    distribution = None,
                )

            withCleanDatabase {
                insertVersionWithId(gradle810)

                withTestApplication {
                    // when: GET single version
                    client.get("/versions/gradle/8.10").apply {
                        // then: response includes empty tags array
                        status shouldBe HttpStatusCode.OK
                        val expected = gradle810.copy(tags = emptyList<String>().some()).toJson()
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe expected
                    }
                }
            }
        }

        should("GET single version with multiple tags returns all tags") {
            // given: a version with multiple tags
            val java2702 =
                Version(
                    candidate = "java",
                    version = "27.0.2",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-27.0.2-temurin",
                    visible = true.some(),
                )

            withCleanDatabase {
                val id = insertVersionWithId(java2702)
                insertTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)
                insertTag("java", "27", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)
                insertTag("java", "27.0", Distribution.TEMURIN.some(), Platform.LINUX_X64, id)

                withTestApplication {
                    // when: GET single version
                    client.get("/versions/java/27.0.2?platform=linuxx64&distribution=TEMURIN").apply {
                        // then: all three tags appear
                        status shouldBe HttpStatusCode.OK
                        val body = Json.decodeFromString<JsonObject>(bodyAsText())
                        val expected = java2702.copy(tags = listOf("latest", "27", "27.0").some()).toJson()
                        body shouldBe expected
                    }
                }
            }
        }
    })
