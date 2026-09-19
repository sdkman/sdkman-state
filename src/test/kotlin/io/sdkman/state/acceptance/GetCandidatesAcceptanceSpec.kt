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

/**
 * End-to-end cover of `GET /candidates`.
 *
 * The response is read as raw JSON rather than decoded into `CandidateDto`, because the contract
 * says an unresolved `default` is *absent* and a decode cannot tell an absent field from a null
 * one — both land on `none()`.
 *
 * No request in this spec carries an `Authorization` header: the route is public, so needing no
 * token is a property of every case here rather than of one.
 */
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
                // given: an empty registry, which is the state the table ships in (business rule 13)
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

                    // then: the order is ascending by identifier — the Candidates Service renders
                    // `sdk list` straight from it, so the order is part of the contract (rule 11)
                    response.bodyAsText().candidateEntries().identifiers() shouldBe listOf("ant", "gradle", "scala")
                }
            }
        }

        should("derive the default of a candidate from its lts tag on UNIVERSAL") {
            withCleanDatabase {
                // given: a registered candidate whose UNIVERSAL row carries the lts tag
                insertCandidates(registrationOf("gradle"))
                insertVersionTaggedLts("gradle", "8.14", Platform.UNIVERSAL)

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the default is derived from `version_tags`, never stored (rule 4)
                    response.bodyAsText().candidateEntries().defaultOf("gradle") shouldBe "8.14".some()
                }
            }
        }

        should("fall back to the LINUX_X64 lts tag when no UNIVERSAL row is tagged") {
            withCleanDatabase {
                // given: an untagged UNIVERSAL row alongside the tagged LINUX_X64 one, so the case
                // separates "no UNIVERSAL row tagged lts" from "no UNIVERSAL row at all"
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
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the second platform of the resolution order answers (rule 5)
                    response.bodyAsText().candidateEntries().defaultOf("kuml") shouldBe "0.20.5".some()
                }
            }
        }

        should("omit the default of java even when its lts row would otherwise resolve") {
            withCleanDatabase {
                // given: a java row that satisfies every other rule — no distribution, on UNIVERSAL —
                // so only the by-name exclusion (rule 8) can keep the default away
                insertCandidates(registrationOf("java"))
                insertVersionTaggedLts("java", "25.0.4", Platform.UNIVERSAL)

                withTestApplication {
                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: java is listed — `single` fails if it is not — with the field absent,
                    // not null: `sdk list` reads an absent default as "unset"
                    val javaEntry = response.bodyAsText().candidateEntries().single()
                    javaEntry.keys shouldNotContain "default"
                }
            }
        }

        should("omit a candidate that has versions but is not registered") {
            withCleanDatabase {
                // given: a version row for `cuba`, which no registry row names. Until the backfill
                // runs this is the normal state of the service, not an edge case (rule 13)
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

                    // then: the orphan is inert — it is unreachable from `sdk list` and still
                    // resolves by exact identifier on the download path
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

                    // then: the route caches like the version read routes, and the plugin value
                    // arrives alone — the route declares no `Cache-Control` of its own
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("max-age=600")
                }
            }
        }
    })
