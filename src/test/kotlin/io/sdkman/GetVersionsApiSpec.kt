package io.sdkman

import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.insertVersions
import io.sdkman.support.toJson
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class GetVersionsApiSpec :
    ShouldSpec({

        should("GET all versions for a candidate") {
            val java17linuxArm64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )
            val java17linuxX64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )

            withCleanDatabase {
                insertVersions(java17linuxArm64, java21linuxArm64, java17linuxX64, java21linuxX64)
                withTestApplication {
                    client.get("/versions/java").apply {
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe
                            JsonArray(
                                listOf(
                                    java17linuxX64.toJson(),
                                    java17linuxArm64.toJson(),
                                    java21linuxX64.toJson(),
                                    java21linuxArm64.toJson(),
                                ),
                            )
                    }
                }
            }
        }

        should("GET versions based on visibility for a candidate") {
            val java17linuxArm64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-17.0.1-tem",
                    visible = false.some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )

            listOf(
                "true" to listOf(java21linuxArm64),
                "false" to listOf(java17linuxArm64),
                "all" to listOf(java17linuxArm64, java21linuxArm64),
            ).forEach { (visible, versions) ->
                withCleanDatabase {
                    insertVersions(java17linuxArm64, java21linuxArm64)
                    withTestApplication {
                        client
                            .get("/versions/java") {
                                url {
                                    parameters.append("visible", visible)
                                }
                            }.apply {
                                status shouldBe HttpStatusCode.OK
                                Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(versions.map { it.toJson() })
                            }
                    }
                }
            }
        }

        should("GET all versions for a universal candidate") {
            val java17universal =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                )
            val java21universal =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )

            withCleanDatabase {
                insertVersions(java17universal, java21universal)
                withTestApplication {
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "universal") }
                        }.apply {
                            status shouldBe HttpStatusCode.OK
                            Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe
                                JsonArray(
                                    listOf(
                                        java17universal.toJson(),
                                        java21universal.toJson(),
                                    ),
                                )
                        }
                }
            }
        }

        should("GET all versions for a multi-platform candidate") {
            val java17linuxArm64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )
            val java17linuxX64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )

            withCleanDatabase {
                insertVersions(java17linuxArm64, java21linuxArm64, java17linuxX64, java21linuxX64)
                withTestApplication {
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "linuxarm64") }
                        }.apply {
                            status shouldBe HttpStatusCode.OK
                            Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe
                                JsonArray(
                                    listOf(
                                        java17linuxArm64.toJson(),
                                        java21linuxArm64.toJson(),
                                    ),
                                )
                        }
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "linuxx64") }
                        }.apply {
                            status shouldBe HttpStatusCode.OK
                            Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe
                                JsonArray(
                                    listOf(
                                        java17linuxX64.toJson(),
                                        java21linuxX64.toJson(),
                                    ),
                                )
                        }
                }
            }
        }

        should("GET versions based on visibility for a platform specified candidate") {
            val java17linuxX64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-17.0.1-tem",
                    visible = false.some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                )

            listOf(
                "true" to listOf(java21linuxX64),
                "false" to listOf(java17linuxX64),
                "all" to listOf(java17linuxX64, java21linuxX64),
            ).forEach { (visible, versions) ->
                withCleanDatabase {
                    insertVersions(java17linuxX64, java21linuxX64)
                    withTestApplication {
                        client
                            .get("/versions/java") {
                                url {
                                    parameters.append("platform", "universal")
                                    parameters.append("visible", visible)
                                }
                            }.apply {
                                status shouldBe HttpStatusCode.OK
                                Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe JsonArray(versions.map { it.toJson() })
                            }
                    }
                }
            }
        }
    })
