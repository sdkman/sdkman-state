package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.none
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertCandidates
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class GetCandidatesAcceptanceSpec :
    ShouldSpec({
        fun registrationOf(candidate: String): CandidateRegistration =
            CandidateRegistration(
                candidate = candidate,
                name = candidate.replaceFirstChar { it.uppercase() },
                description = "The $candidate candidate.",
                websiteUrl = "https://$candidate.example.com/",
            )

        fun insertVersionTaggedLts(
            candidate: String,
            version: String,
            platform: Platform,
        ) {
            val versionId =
                insertVersionWithId(
                    Version(
                        candidate = candidate,
                        version = version,
                        platform = platform,
                        url = "https://$candidate-$version",
                        visible = true.some(),
                    ),
                )
            insertTag(candidate, "lts", none(), platform, versionId)
        }

        fun String.candidateEntries(): List<JsonObject> = Json.decodeFromString<JsonArray>(this).map { it.jsonObject }

        fun List<JsonObject>.identifiers(): List<String> = map { it.getValue("candidate").jsonPrimitive.content }

        fun List<JsonObject>.defaultOf(candidate: String): Option<String> =
            firstOrNone { it.getValue("candidate").jsonPrimitive.content == candidate }
                .flatMap { entry -> entry["default"].toOption() }
                .map { it.jsonPrimitive.content }

        should("return an empty array when no candidate is registered") {
            withCleanDatabase {
                withTestApplication {
                    val response = client.get("/candidates")

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText().candidateEntries() shouldBe emptyList()
                }
            }
        }

        should("list every registered candidate ascending by identifier") {
            withCleanDatabase {
                insertCandidates(
                    registrationOf("gradle"),
                    registrationOf("scala"),
                    registrationOf("ant"),
                )

                withTestApplication {
                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().identifiers() shouldBe listOf("ant", "gradle", "scala")
                }
            }
        }

        should("derive the default of a candidate from its lts tag on UNIVERSAL") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))
                insertVersionTaggedLts("gradle", "8.14", Platform.UNIVERSAL)

                withTestApplication {
                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().defaultOf("gradle") shouldBe "8.14".some()
                }
            }
        }

        should("fall back to the LINUX_X64 lts tag when no UNIVERSAL row is tagged") {
            withCleanDatabase {
                insertCandidates(registrationOf("kuml"))
                insertVersionWithId(
                    Version(
                        candidate = "kuml",
                        version = "0.21.0",
                        platform = Platform.UNIVERSAL,
                        url = "https://kuml-0.21.0",
                        visible = true.some(),
                    ),
                )
                insertVersionTaggedLts("kuml", "0.20.5", Platform.LINUX_X64)

                withTestApplication {
                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().defaultOf("kuml") shouldBe "0.20.5".some()
                }
            }
        }

        should("omit the default of java even when its lts row would otherwise resolve") {
            withCleanDatabase {
                insertCandidates(registrationOf("java"))
                insertVersionTaggedLts("java", "25.0.4", Platform.UNIVERSAL)

                withTestApplication {
                    val response = client.get("/candidates")

                    val javaEntry = response.bodyAsText().candidateEntries().single()
                    javaEntry.keys shouldNotContain "default"
                }
            }
        }

        should("omit a candidate that has versions but is not registered") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))
                insertVersions(
                    Version(
                        candidate = "cuba",
                        version = "1.0.1",
                        platform = Platform.UNIVERSAL,
                        url = "https://cuba-1.0.1",
                        visible = true.some(),
                    ),
                )

                withTestApplication {
                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().identifiers() shouldNotContain "cuba"
                }
            }
        }

        should("return exactly one Cache-Control value of max-age=600 to an anonymous client") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))

                withTestApplication {
                    val response = client.get("/candidates")

                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("max-age=600")
                }
            }
        }
    })
