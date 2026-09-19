package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.adapter.primary.rest.dto.CandidateConflictResponse
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertCandidates
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.selectAuditRecords
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outcomes are read back through `GET /candidates` as well as from the delete response: the
 * status alone cannot tell a refused delete from one that answered `409` after removing the row.
 *
 * Rows are seeded with `insertCandidates` rather than over `POST /admin/candidates`, so a
 * regression in the write route fails its own spec and not this one. The audit case is the
 * exception — it names a registration, so it drives the write route on purpose.
 */
@Tags("acceptance")
class AdminCandidateDeletionAcceptanceSpec :
    ShouldSpec({

        fun registrationOf(candidate: String): CandidateRegistration =
            CandidateRegistration(
                candidate = candidate,
                name = candidate.replaceFirstChar { it.uppercase() },
                description = "The $candidate candidate.",
                websiteUrl = "https://$candidate.example.com/",
            )

        fun versionOf(
            candidate: String,
            version: String,
        ): Version =
            Version(
                candidate = candidate,
                version = version,
                platform = Platform.UNIVERSAL,
                url = "https://$candidate-$version",
                visible = true.some(),
            )

        val registrationBody =
            """
            {"candidate":"jbang","name":"JBang","description":"Java scripting.","website_url":"https://jbang.dev/"}
            """.trimIndent()

        fun String.candidateEntries(): List<JsonObject> = Json.decodeFromString<JsonArray>(this).map { it.jsonObject }

        fun List<JsonObject>.identifiers(): List<String> = map { it.getValue("candidate").jsonPrimitive.content }

        should("answer 200 when the candidate has no versions") {
            withCleanDatabase {
                // given: a registered candidate with no versions
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: an admin deletes it
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a `200` carrying the removed record, not the version route's `204`
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("remove a deleted candidate from the listing") {
            withCleanDatabase {
                // given: a registered candidate with no versions
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // given: an admin deletes it
                    client.delete("/admin/candidates/jbang") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: deletion is hard — the row is gone, not hidden behind a flag
                    response.bodyAsText().candidateEntries().identifiers() shouldNotContain "jbang"
                }
            }
        }

        should("answer 409 when the candidate has versions") {
            withCleanDatabase {
                // given: a registered candidate with one published version
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"))

                withTestApplication {
                    // when: an admin deletes it
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: no foreign key stands behind this, so the count is the whole guard
                    response.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        should("keep a candidate that has versions in the listing") {
            withCleanDatabase {
                // given: a registered candidate with one published version
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"))

                withTestApplication {
                    // given: an admin attempts to delete it
                    client.delete("/admin/candidates/gradle") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: a `409` raised after the delete had run would pass the status case
                    // above and fail here
                    response.bodyAsText().candidateEntries().identifiers() shouldContain "gradle"
                }
            }
        }

        should("carry the version count in the 409 body") {
            withCleanDatabase {
                // given: a registered candidate with two published versions
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"), versionOf("gradle", "8.13"))

                withTestApplication {
                    // when: an admin deletes it
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the count is a field, so a caller need not parse the message
                    val body = Json.decodeFromString<CandidateConflictResponse>(response.bodyAsText())
                    body.versionCount shouldBe 2L
                }
            }
        }

        should("answer 404 for an unknown candidate") {
            withCleanDatabase {
                // given: an empty registry
                withTestApplication {
                    // when: an admin deletes a candidate no row names
                    val response =
                        client.delete("/admin/candidates/nonesuch") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a `404`, never a `409` — the count is reached only once the row is
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("answer 401 to an anonymous client") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: a client with no token deletes a candidate
                    val response = client.delete("/admin/candidates/jbang")

                    // then: the route is admin-only
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to a vendor token") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: a vendor authorised for the candidate deletes it
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("jbang")))
                        }

                    // then: the token authenticates, so this refusal is the route's role check
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to an expired token") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: an admin presents a token that has expired
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.expiredToken())
                        }

                    // then: the admin role in the claims does not outlive the expiry
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return exactly one Cache-Control value of no-store") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: an admin deletes a candidate
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: asserted over all values — reading by name would return the first
                    // and hide a `max-age` appended after it
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        should("return no Expires header") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    // when: an admin deletes a candidate
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: nothing makes an admin write cacheable, not even a past expiry
                    response.headers[HttpHeaders.Expires].toOption() shouldBe none()
                }
            }
        }

        should("write no audit row for a registration and a deletion") {
            withCleanDatabase {
                withTestApplication {
                    // given: an admin registers a candidate over the write route
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody)
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: the same admin deletes it again
                    client.delete("/admin/candidates/jbang") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // then: candidate writes are recorded by the row timestamps alone
                    selectAuditRecords() shouldBe emptyList()
                }
            }
        }
    })
