package io.sdkman.state.acceptance

import arrow.core.none
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
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.toJson
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Tags("acceptance")
class ResolveVersionByTagAcceptanceSpec :
    ShouldSpec({

        should("resolve a fully-scoped tag for a candidate with distribution") {
            val version =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-25.0.2-temurin-linux-x64.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    tags = listOf("lts").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, versionId)
                withTestApplication {
                    client.get("/versions/java/tags/lts?distribution=TEMURIN&platform=LINUX_X64").apply {
                        // then: resolves to the tagged version
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe version.toJson()
                    }
                }
            }
        }

        should("return 404 when tag does not exist in the given scope") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/tags/lts?distribution=TEMURIN&platform=LINUX_X64").apply {
                        // then: tag not found
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

        should("resolve tag for a candidate without distribution") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.6.4",
                    platform = Platform.UNIVERSAL,
                    url = "https://scala-3.6.4.tar.gz",
                    visible = true.some(),
                    distribution = none(),
                    tags = listOf("latest").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("scala", "latest", none(), Platform.UNIVERSAL, versionId)
                withTestApplication {
                    client.get("/versions/scala/tags/latest?platform=UNIVERSAL").apply {
                        // then: resolves without distribution at the explicitly requested UNIVERSAL platform
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe version.toJson()
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when platform parameter is missing") {
            // Rule 5: the previous implicit UNIVERSAL fall-back is gone — platform is required,
            // and absence is a client error that must name the missing parameter and the
            // canonical vocabulary in the body, never coerced silently to UNIVERSAL.
            val version =
                Version(
                    candidate = "gradle",
                    version = "8.12",
                    platform = Platform.UNIVERSAL,
                    url = "https://gradle-8.12.tar.gz",
                    visible = true.some(),
                    distribution = none(),
                    tags = listOf("latest").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("gradle", "latest", none(), Platform.UNIVERSAL, versionId)
                withTestApplication {
                    client.get("/versions/gradle/tags/latest").apply {
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse(
                                "Bad Request",
                                "Missing required parameter: platform. Expected one of: " +
                                    "LINUX_X32, LINUX_X64, LINUX_ARM32HF, LINUX_ARM32SF, LINUX_ARM64, " +
                                    "MAC_X64, MAC_ARM64, WINDOWS_X64, UNIVERSAL.",
                            )
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when platform is a retired legacy identifier") {
            // Rule 3: unknown platform — including legacy lowercase identifiers like `linuxx64` —
            // is a client error on tag resolution, never silently coerced to UNIVERSAL.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/tags/lts?platform=linuxx64").apply {
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

        should("resolve a universal-tagged version when platform=UNIVERSAL is supplied explicitly") {
            val version =
                Version(
                    candidate = "gradle",
                    version = "8.12",
                    platform = Platform.UNIVERSAL,
                    url = "https://gradle-8.12.tar.gz",
                    visible = true.some(),
                    distribution = none(),
                    tags = listOf("latest").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("gradle", "latest", none(), Platform.UNIVERSAL, versionId)
                withTestApplication {
                    client.get("/versions/gradle/tags/latest?platform=UNIVERSAL").apply {
                        // then: UNIVERSAL is now reachable only when explicitly requested
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe version.toJson()
                    }
                }
            }
        }

        should("scope tag resolution to distribution") {
            val temurinVersion =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-25.0.2-temurin.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    tags = listOf("lts").some(),
                )
            val correttoVersion =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-25.0.2-corretto.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.CORRETTO.some(),
                    tags = listOf("lts").some(),
                )

            withCleanDatabase {
                val temurinId = insertVersionWithId(temurinVersion)
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, temurinId)
                val correttoId = insertVersionWithId(correttoVersion)
                insertTag("java", "lts", Distribution.CORRETTO.some(), Platform.LINUX_X64, correttoId)
                withTestApplication {
                    client.get("/versions/java/tags/lts?distribution=TEMURIN&platform=LINUX_X64").apply {
                        // then: resolves to the TEMURIN version
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe temurinVersion.toJson()
                    }
                }
            }
        }

        should("return 404 when tag case does not match (case-sensitive)") {
            val version =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-25.0.2-temurin.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, versionId)
                withTestApplication {
                    client.get("/versions/java/tags/LTS?distribution=TEMURIN&platform=LINUX_X64").apply {
                        // then: LTS != lts, so 404
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when candidate is blank") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/%20/tags/lts").apply {
                        // then: blank candidate names the offending path parameter
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse("Bad Request", "Missing required path parameter: candidate")
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when tag is blank") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/tags/%20").apply {
                        // then: blank tag names the offending path parameter
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse("Bad Request", "Missing required path parameter: tag")
                    }
                }
            }
        }

        should("return tagged version regardless of visibility") {
            val version =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://java-25.0.2-temurin.tar.gz",
                    visible = false.some(),
                    distribution = Distribution.TEMURIN.some(),
                    tags = listOf("lts").some(),
                )

            withCleanDatabase {
                val versionId = insertVersionWithId(version)
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, versionId)
                withTestApplication {
                    client.get("/versions/java/tags/lts?distribution=TEMURIN&platform=LINUX_X64").apply {
                        // then: invisible version is still returned
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe version.toJson()
                    }
                }
            }
        }
    })
