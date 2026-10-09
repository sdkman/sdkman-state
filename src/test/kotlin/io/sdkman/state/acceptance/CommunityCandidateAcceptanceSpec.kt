package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        fun registrationOf(candidate: String): CandidateRegistration =
            CandidateRegistration(
                candidate = candidate,
                name = candidate.replaceFirstChar { it.uppercase() },
                description = "The $candidate candidate.",
                websiteUrl = "https://$candidate.example.com/",
            )

        val jpxRegistrationBody =
            """
            {"candidate":"jpx","name":"Jpx","description":"Java project executor.","website_url":"https://jpx.example.com/"}
            """.trimIndent()

        fun String.candidateIdentifiers(): List<String> =
            Json.decodeFromString<JsonArray>(this).map {
                it.jsonObject
                    .getValue("candidate")
                    .jsonPrimitive.content
            }

        fun observerToken(): String = JwtTestSupport.tokenWithClaims(sub = "observer@example.com", role = "observer")

        should("answer 201 when the community registers a new candidate") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxRegistrationBody)
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.Created
                    client.get("/candidates").bodyAsText().candidateIdentifiers() shouldContain "jpx"
                }
            }
        }

        should("answer 200 when the community deletes a candidate without versions") {
            withCleanDatabase {
                insertCandidates(registrationOf("jpx"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jpx") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.OK
                    client.get("/candidates").bodyAsText().candidateIdentifiers() shouldNotContain "jpx"
                }
            }
        }

        should("answer 409 when the community deletes a candidate with versions") {
            withCleanDatabase {
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
                    val response =
                        client.delete("/admin/candidates/gradle") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.Conflict
                    client.get("/candidates").bodyAsText().candidateIdentifiers() shouldContain "gradle"
                }
            }
        }

        should("answer 401 when the community lists vendors") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 when the community creates a vendor") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"vendor@test.com","candidates":["jpx"]}""")
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }.bodyAsText() shouldBe "[]"
                }
            }
        }

        should("answer 401 when the community deletes a vendor") {
            withCleanDatabase {
                withTestApplication {
                    val vendorId =
                        Json
                            .parseToJsonElement(
                                client
                                    .post("/admin/vendors") {
                                        contentType(ContentType.Application.Json)
                                        setBody("""{"email":"vendor@test.com","candidates":["jpx"]}""")
                                        bearerAuth(JwtTestSupport.adminToken())
                                    }.bodyAsText(),
                            ).jsonObject
                            .getValue("id")
                            .jsonPrimitive.content

                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            bearerAuth(JwtTestSupport.communityToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    client
                        .get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }.bodyAsText() shouldContain vendorId
                }
            }
        }

        should("answer 401 when an unknown role registers a candidate") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(jpxRegistrationBody)
                            bearerAuth(observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 when an unknown role deletes a candidate") {
            withCleanDatabase {
                insertCandidates(registrationOf("jpx"))

                withTestApplication {
                    val response =
                        client.delete("/admin/candidates/jpx") {
                            bearerAuth(observerToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    client.get("/candidates").bodyAsText().candidateIdentifiers() shouldContain "jpx"
                }
            }
        }
    })
