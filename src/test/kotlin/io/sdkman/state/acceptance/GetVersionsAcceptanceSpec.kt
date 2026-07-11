package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.toJson
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

@Tags("acceptance")
class GetVersionsAcceptanceSpec :
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
                    tags = emptyList<String>().some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )
            val java17linuxX64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
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
                    tags = emptyList<String>().some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
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
                    tags = emptyList<String>().some(),
                )
            val java21universal =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )

            withCleanDatabase {
                insertVersions(java17universal, java21universal)
                withTestApplication {
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "UNIVERSAL") }
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
                    tags = emptyList<String>().some(),
                )
            val java21linuxArm64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_ARM64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )
            val java17linuxX64 =
                Version(
                    candidate = "java",
                    version = "17.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-17.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
                )

            withCleanDatabase {
                insertVersions(java17linuxArm64, java21linuxArm64, java17linuxX64, java21linuxX64)
                withTestApplication {
                    client
                        .get("/versions/java") {
                            url { parameters.append("platform", "LINUX_ARM64") }
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
                            url { parameters.append("platform", "LINUX_X64") }
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

        should("return 400 with ErrorResponse body when platform is a retired legacy identifier") {
            // Rules 2 & 3: the lowercase platform identifier `linuxx64` is no longer accepted —
            // an unknown value must surface as a descriptive 400, never silently fall through
            // to UNIVERSAL as the old loose parser did.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java?platform=linuxx64").apply {
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse(
                                "Bad Request",
                                "Invalid platform 'linuxx64'. Expected one of: " +
                                    "LINUX_X32, LINUX_X64, LINUX_ARM32HF, LINUX_ARM32SF, LINUX_ARM64, " +
                                    "MAC_X64, MAC_ARM64, WINDOWS_X64, UNIVERSAL.",
                            )
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when distribution is a vendor shortcode") {
            // Rules 9 & 10: vendor shortcodes such as `open` are no longer silently dropped —
            // that previously discarded the filter and broadened the result. They must now be
            // rejected with a message naming the offending value and the canonical vocabulary.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java?distribution=open").apply {
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse(
                                "Bad Request",
                                "Invalid distribution 'open'. Expected one of: " +
                                    "BISHENG, CORRETTO, ELIYA, GRAALCE, GRAALVM, JETBRAINS, KONA, LIBERICA, " +
                                    "LIBERICA_NIK, MANDREL, MICROSOFT, OPENJDK, ORACLE, SAP_MACHINE, " +
                                    "SEMERU, TEMURIN, ZULU.",
                            )
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when visible value is outside the vocabulary") {
            // Rule 14: an unknown `visible` value is no longer coerced to `true`. The accepted
            // set is fixed (`true`, `false`, `all`) and anything else is a client error.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java?visible=yes").apply {
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse(
                                "Bad Request",
                                "Invalid visible 'yes'. Expected one of: true, false, all.",
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
                    tags = emptyList<String>().some(),
                )
            val java21linuxX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.UNIVERSAL,
                    url = "https://java-21.0.1-tem",
                    visible = true.some(),
                    tags = emptyList<String>().some(),
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
                                    parameters.append("platform", "UNIVERSAL")
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
