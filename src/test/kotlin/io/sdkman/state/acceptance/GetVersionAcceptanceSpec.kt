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
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.toJson
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Tags("acceptance")
class GetVersionAcceptanceSpec :
    ShouldSpec({

        should("GET a UNIVERSAL version for a candidate") {
            val kotlin210Universal =
                Version(
                    candidate = "kotlin",
                    version = "2.1.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://kotlin-2.1.0-tem",
                    visible = true.some(),
                    distribution = none(),
                    tags = emptyList<String>().some(),
                )

            withCleanDatabase {
                insertVersions(kotlin210Universal)
                withTestApplication {
                    client.get("/versions/kotlin/2.1.0?platform=UNIVERSAL").apply {
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe kotlin210Universal.toJson()
                    }
                }
            }
        }

        should("GET a platform-specific version for a candidate") {
            val java21MacX64 =
                Version(
                    candidate = "java",
                    version = "21.0.1",
                    platform = Platform.MAC_X64,
                    url = "https://java-21.0.1-mac-x64",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    tags = emptyList<String>().some(),
                )

            withCleanDatabase {
                insertVersions(java21MacX64)
                withTestApplication {
                    client.get("/versions/java/21.0.1?platform=MAC_X64&distribution=TEMURIN").apply {
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe java21MacX64.toJson()
                    }
                }
            }
        }

        should("GET a version with NO distribution for a candidate") {
            val scala312Universal =
                Version(
                    candidate = "scala",
                    version = "3.1.2",
                    platform = Platform.UNIVERSAL,
                    url = "https://scala-3.1.2",
                    visible = true.some(),
                    distribution = none(),
                    tags = emptyList<String>().some(),
                )

            withCleanDatabase {
                insertVersions(scala312Universal)
                withTestApplication {
                    client.get("/versions/scala/3.1.2?platform=UNIVERSAL").apply {
                        status shouldBe HttpStatusCode.OK
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe scala312Universal.toJson()
                    }
                }
            }
        }

        should("return NOT_FOUND when platform-specific version does not exist") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/21.0.1?platform=MAC_X64&distribution=TEMURIN").apply {
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

        should("return NOT_FOUND when version with NO distribution does not exist") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/scala/3.1.2?platform=UNIVERSAL").apply {
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

        should("return 400 with ErrorResponse body when platform parameter is missing") {
            // Rule 4: platform is required on single-version resolution — the previous implicit
            // UNIVERSAL fallback is gone, and absence must surface with a message naming the
            // missing parameter and the canonical vocabulary.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/21.0.3").apply {
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
            // is a client error, never silently coerced to UNIVERSAL.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/21.0.3?platform=linuxx64").apply {
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
            // Rule 10: vendor shortcodes such as `open` are no longer silently dropped — that
            // discarded the filter and broadened the match. They must now be rejected with a
            // descriptive message naming the offending value and the canonical vocabulary.
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/java/21.0.3?platform=LINUX_X64&distribution=open").apply {
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

        should("return BAD_REQUEST with ErrorResponse body when candidate parameter is blank") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/%20/1.0.0").apply {
                        status shouldBe HttpStatusCode.BadRequest
                        Json.decodeFromString<ErrorResponse>(bodyAsText()) shouldBe
                            ErrorResponse("Bad Request", "Missing required path parameter: candidate")
                    }
                }
            }
        }

        should("return NOT_FOUND when version parameter is missing (invalid route)") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/scala/").apply {
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }

        should("return NOT_FOUND when accessing non-existent route") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/versions/scala/3.1.2/extra").apply {
                        status shouldBe HttpStatusCode.NotFound
                    }
                }
            }
        }
    })
