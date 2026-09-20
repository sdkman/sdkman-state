package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.toOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class AdminCandidateRegistrationAcceptanceSpec :
    ShouldSpec({
        fun registrationBody(
            candidate: String = "jbang",
            name: String = "JBang",
            description: String = "Java scripting, without a build.",
            websiteUrl: String = "https://jbang.dev/",
        ): String =
            """
            {"candidate":"$candidate","name":"$name","description":"$description","website_url":"$websiteUrl"}
            """.trimIndent()

        fun String.candidateEntries(): List<JsonObject> = Json.decodeFromString<JsonArray>(this).map { it.jsonObject }

        fun List<JsonObject>.nameOf(candidate: String): String =
            single { it.getValue("candidate").jsonPrimitive.content == candidate }
                .getValue("name")
                .jsonPrimitive.content

        should("answer 201 when the candidate is new") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers an unknown candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the upsert reports a new row
                    response.status shouldBe HttpStatusCode.Created
                }
            }
        }

        should("list a newly registered candidate") {
            withCleanDatabase {
                withTestApplication {
                    // given: an admin registers a candidate
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the write reached the table the public read serves
                    response.bodyAsText().candidateEntries().nameOf("jbang") shouldBe "JBang"
                }
            }
        }

        should("answer 200 when the candidate already exists") {
            withCleanDatabase {
                withTestApplication {
                    // given: the candidate is already registered
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: an admin posts the same identifier with a new name
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(name = "JBang!"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a repeat post updates rather than conflicts
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("update the listed metadata when the candidate is re-registered") {
            withCleanDatabase {
                withTestApplication {
                    // given: the candidate is already registered under its original name
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: an admin posts the same identifier with a new name
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody(name = "JBang!"))
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // then: `single` in `nameOf` fails if the upsert inserted a duplicate
                    val response = client.get("/candidates")
                    response.bodyAsText().candidateEntries().nameOf("jbang") shouldBe "JBang!"
                }
            }
        }

        should("reject a website_url that is not https") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers a candidate with a plain http website
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(websiteUrl = "http://jbang.dev/"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the https rule covers `website_url` too
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject an uppercase candidate identifier") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers `JBang` rather than `jbang`
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(candidate = "JBang"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: identifiers are case-sensitive, so this is not a spelling of `jbang`
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject an over-long description") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers a candidate described in 2001 characters
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "d".repeat(2001)))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the bound is refused at the route, not at the column
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description carrying a trademark symbol") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers a candidate described with a non-ASCII codepoint
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "Apache Tomcat® software."))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a non-ASCII codepoint is mojibake in `sdk list`'s terminal box
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description spanning two lines") {
            withCleanDatabase {
                withTestApplication {
                    // when: a description carries a line break. The `\n` is a JSON escape, so
                    // the decoded string holds a real newline rather than two characters
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = """Java scripting.\nWithout a build."""))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a description is a single paragraph
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description carrying consecutive spaces") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers a candidate described with a double space
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "Java scripting,  without a build."))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a run of spaces is refused on write rather than normalised
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("answer 400 for malformed JSON") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin posts a body that is not valid JSON
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"jbang",""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a bad body is an ordinary validation failure, not a `500`
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("answer a ValidationErrorResponse body for malformed JSON") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin posts a body that is not valid JSON
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"jbang",""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the strict decode is itself the assertion that this is no `ErrorResponse`
                    val body = Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText())
                    body.failures.map { it.field } shouldBe listOf("request")
                }
            }
        }

        should("answer 401 to an anonymous client") {
            withCleanDatabase {
                withTestApplication {
                    // when: a client with no token registers a candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                        }

                    // then: the route is admin-only
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to a vendor token") {
            withCleanDatabase {
                withTestApplication {
                    // when: a vendor authorised for a candidate registers one
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("jbang")))
                        }

                    // then: the token authenticates, so this refusal is the route's role check
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to an expired token") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin presents a token that has expired
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.expiredToken())
                        }

                    // then: the admin role in the claims does not outlive the expiry
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return exactly one Cache-Control value of no-store") {
            withCleanDatabase {
                withTestApplication {
                    // when: an admin registers a candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
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
                withTestApplication {
                    // when: an admin registers a candidate
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: nothing makes an admin write cacheable, not even a past expiry
                    response.headers[HttpHeaders.Expires].toOption() shouldBe none()
                }
            }
        }
    })
