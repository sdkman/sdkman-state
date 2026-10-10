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

        fun insertVersionTagged(
            candidate: String,
            version: String,
            platform: Platform,
            tag: String,
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
            insertTag(candidate, tag, none(), platform, versionId)
        }

        fun String.candidateEntries(): List<JsonObject> = Json.decodeFromString<JsonArray>(this).map { it.jsonObject }

        fun List<JsonObject>.identifiers(): List<String> = map { it.getValue("candidate").jsonPrimitive.content }

        fun List<JsonObject>.defaultOf(candidate: String): Option<String> =
            firstOrNone { it.getValue("candidate").jsonPrimitive.content == candidate }
                .flatMap { entry -> entry["default"].toOption() }
                .map { it.jsonPrimitive.content }

        should("return an empty array when no candidate is registered") {
            withCleanDatabase {
                // given: an empty registry, which is the state the table ships in
                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the listing is empty rather than an error
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText().candidateEntries() shouldBe emptyList()
                }
            }
        }

        should("list every registered candidate ascending by identifier") {
            withCleanDatabase {
                // given: three candidates registered out of order
                insertCandidates(
                    registrationOf("gradle"),
                    registrationOf("scala"),
                    registrationOf("ant"),
                )

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: ascending by identifier, which is part of the public contract
                    response.bodyAsText().candidateEntries().identifiers() shouldBe listOf("ant", "gradle", "scala")
                }
            }
        }

        should("derive the default of a candidate from its lts tag on UNIVERSAL") {
            withCleanDatabase {
                // given: a registered candidate whose UNIVERSAL row carries the lts tag
                insertCandidates(registrationOf("gradle"))
                insertVersionTagged("gradle", "8.14", Platform.UNIVERSAL, "lts")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the default is derived from `version_tags`, never stored
                    response.bodyAsText().candidateEntries().defaultOf("gradle") shouldBe "8.14".some()
                }
            }
        }

        should("fall back to the LINUX_X64 lts tag when no UNIVERSAL row is tagged") {
            withCleanDatabase {
                // given: an untagged UNIVERSAL row alongside the tagged LINUX_X64 one, separating
                // "no UNIVERSAL row tagged lts" from "no UNIVERSAL row at all"
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
                insertVersionTagged("kuml", "0.20.5", Platform.LINUX_X64, "lts")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the second platform of the resolution order answers
                    response.bodyAsText().candidateEntries().defaultOf("kuml") shouldBe "0.20.5".some()
                }
            }
        }

        should("derive the default of a candidate from its stable tag on UNIVERSAL") {
            withCleanDatabase {
                // given: a registered candidate whose UNIVERSAL row carries the stable tag
                insertCandidates(registrationOf("gradle"))
                insertVersionTagged("gradle", "8.14", Platform.UNIVERSAL, "stable")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the stable tag produces the default
                    response.bodyAsText().candidateEntries().defaultOf("gradle") shouldBe "8.14".some()
                }
            }
        }

        should("prefer the stable tag over the lts tag on UNIVERSAL") {
            withCleanDatabase {
                // given: different UNIVERSAL versions tagged stable and lts
                insertCandidates(registrationOf("gradle"))
                insertVersionTagged("gradle", "8.14", Platform.UNIVERSAL, "stable")
                insertVersionTagged("gradle", "9.8.1", Platform.UNIVERSAL, "lts")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: stable wins
                    response.bodyAsText().candidateEntries().defaultOf("gradle") shouldBe "8.14".some()
                }
            }
        }

        should("prefer the stable tag on LINUX_X64 over the lts tag on UNIVERSAL") {
            withCleanDatabase {
                // given: stable only on the fallback platform, lts on the preferred one
                insertCandidates(registrationOf("kuml"))
                insertVersionTagged("kuml", "0.20.5", Platform.LINUX_X64, "stable")
                insertVersionTagged("kuml", "0.21.0", Platform.UNIVERSAL, "lts")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the tag decides before the platform
                    response.bodyAsText().candidateEntries().defaultOf("kuml") shouldBe "0.20.5".some()
                }
            }
        }

        should("prefer the UNIVERSAL stable tag when stable exists on both platforms") {
            withCleanDatabase {
                // given: different versions tagged stable on UNIVERSAL and LINUX_X64
                insertCandidates(registrationOf("scala"))
                insertVersionTagged("scala", "3.4.3", Platform.UNIVERSAL, "stable")
                insertVersionTagged("scala", "3.3.1", Platform.LINUX_X64, "stable")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: within the stable tag, UNIVERSAL wins
                    response.bodyAsText().candidateEntries().defaultOf("scala") shouldBe "3.4.3".some()
                }
            }
        }

        should("omit the default of java even when its lts row would otherwise resolve") {
            withCleanDatabase {
                // given: a java row satisfying every other rule — no distribution, on UNIVERSAL —
                // so only the by-name exclusion can keep the default away
                insertCandidates(registrationOf("java"))
                insertVersionTagged("java", "25.0.4", Platform.UNIVERSAL, "lts")

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: java is listed — `single` fails if not — with the field absent, not null
                    val javaEntry = response.bodyAsText().candidateEntries().single()
                    javaEntry.keys shouldNotContain "default"
                }
            }
        }

        should("omit a candidate that has versions but is not registered") {
            withCleanDatabase {
                // given: a version row for `cuba`, which no registry row names — the normal state
                // of the service until the backfill runs
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
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the orphan is inert — unreachable from `sdk list`, still resolvable
                    // by exact identifier on the download path
                    response.bodyAsText().candidateEntries().identifiers() shouldNotContain "cuba"
                }
            }
        }

        should("return exactly one Cache-Control value of max-age=600 to an anonymous client") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))

                withTestApplication {
                    // when: a client with no token lists the candidates
                    val response = client.get("/candidates")

                    // then: the plugin value arrives alone — the route declares none of its own
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("max-age=600")
                }
            }
        }
    })
