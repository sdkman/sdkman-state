package io.sdkman.state.acceptance

import arrow.core.some
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
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertCandidates
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class CommunityCandidateAcceptanceSpec :
    ShouldSpec({
        val jpxRegistrationBody =
            """
            {"candidate":"jpx","name":"jpx","description":"GPX library.","website_url":"https://jpx.example.com/"}
            """.trimIndent()
        val vendorBody = """{"email":"gradle-vendor@example.com","candidates":["gradle"]}"""

        fun registrationOf(candidate: String): CandidateRegistration =
            CandidateRegistration(
                candidate = candidate,
                name = candidate.replaceFirstChar { it.uppercase() },
                description = "The $candidate candidate.",
                websiteUrl = "https://$candidate.example.com/",
            )

        fun String.candidateIdentifiers(): List<String> =
            Json.decodeFromString<JsonArray>(this).map {
                it.jsonObject
                    .getValue("candidate")
                    .jsonPrimitive.content
            }

        should("let the community role register a new candidate with 201 Created") {
            withCleanDatabase {
                withTestApplication {
                    // when: the community role registers an unknown candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxRegistrationBody)
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the upsert reports a new row
                    response.status shouldBe HttpStatusCode.Created
                }
            }
        }

        should("let the community role re-register a candidate with 200 OK") {
            withCleanDatabase {
                withTestApplication {
                    // given: the community role has registered the candidate
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(jpxRegistrationBody)
                        bearerAuth(JwtTestSupport.communityToken())
                    }

                    // when: the community role registers it again
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxRegistrationBody)
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the upsert reports an update
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("let the community role delete a candidate without versions with 200 OK") {
            withCleanDatabase {
                // given: a registered candidate with no versions
                insertCandidates(registrationOf("jpx"))

                withTestApplication {
                    // when: the community role deletes it
                    val response =
                        client.delete("/admin/candidates/jpx") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the deletion is accepted and the candidate leaves the listing
                    response.status shouldBe HttpStatusCode.OK
                    client.get("/candidates").bodyAsText().candidateIdentifiers() shouldNotContain "jpx"
                }
            }
        }

        should("refuse the community role deleting a candidate with versions with 409 Conflict") {
            withCleanDatabase {
                // given: a registered candidate with one published version
                insertCandidates(registrationOf("gradle"))
                insertVersions(
                    Version(
                        candidate = "gradle",
                        version = "8.14",
                        platform = Platform.UNIVERSAL,
                        url = "https://gradle-8.14",
                        visible = true.some(),
                    ),
                )

                withTestApplication {
                    // when: the community role deletes it
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: the version guard applies to the community role unchanged
                    response.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        should("refuse the community role listing vendors with 401 Unauthorized") {
            withCleanDatabase {
                withTestApplication {
                    // when: the community role lists vendors
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: vendor management stays admin-only
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("refuse the community role creating a vendor with 401 Unauthorized") {
            withCleanDatabase {
                withTestApplication {
                    // when: the community role creates a vendor
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody(vendorBody)
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: vendor management stays admin-only
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("refuse the community role deleting a vendor with 401 Unauthorized") {
            withCleanDatabase {
                withTestApplication {
                    // given: a vendor created by the admin
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody(vendorBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.content

                    // when: the community role deletes it
                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    // then: vendor management stays admin-only
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("refuse an unknown role registering a candidate with 401 Unauthorized") {
            withCleanDatabase {
                withTestApplication {
                    // when: a validly signed token with role "observer" registers a candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxRegistrationBody)
                            bearerAuth(JwtTestSupport.tokenWithClaims(role = "observer"))
                        }

                    // then: the route admits only the roles it names
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
