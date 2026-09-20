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
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("remove a deleted candidate from the listing") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    client.delete("/admin/candidates/jbang") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().identifiers() shouldNotContain "jbang"
                }
            }
        }

        should("answer 409 when the candidate has versions") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        should("keep a candidate that has versions in the listing") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"))

                withTestApplication {
                    client.delete("/admin/candidates/gradle") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().identifiers() shouldContain "gradle"
                }
            }
        }

        should("carry the version count in the 409 body") {
            withCleanDatabase {
                insertCandidates(registrationOf("gradle"))
                insertVersions(versionOf("gradle", "8.14"), versionOf("gradle", "8.13"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    val body = Json.decodeFromString<CandidateConflictResponse>(response.bodyAsText())
                    body.versionCount shouldBe 2L
                }
            }
        }

        should("answer 404 for an unknown candidate") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/nonesuch") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("answer 401 to an anonymous client") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response = client.delete("/admin/candidates/jbang")

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to a vendor token") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("jbang")))
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to an expired token") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.expiredToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return exactly one Cache-Control value of no-store") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        should("return no Expires header") {
            withCleanDatabase {
                insertCandidates(registrationOf("jbang"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jbang") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.headers[HttpHeaders.Expires].toOption() shouldBe none()
                }
            }
        }

        should("write no audit row for a registration and a deletion") {
            withCleanDatabase {
                withTestApplication {
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody)
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    client.delete("/admin/candidates/jbang") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    selectAuditRecords() shouldBe emptyList()
                }
            }
        }
    })
