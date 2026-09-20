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
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.Created
                }
            }
        }

        should("list a newly registered candidate") {
            withCleanDatabase {
                withTestApplication {
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    val response = client.get("/candidates")

                    response.bodyAsText().candidateEntries().nameOf("jbang") shouldBe "JBang"
                }
            }
        }

        should("answer 200 when the candidate already exists") {
            withCleanDatabase {
                withTestApplication {
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(name = "JBang!"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("update the listed metadata when the candidate is re-registered") {
            withCleanDatabase {
                withTestApplication {
                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    client.post("/admin/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody(name = "JBang!"))
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    val response = client.get("/candidates")
                    response.bodyAsText().candidateEntries().nameOf("jbang") shouldBe "JBang!"
                }
            }
        }

        should("reject a website_url that is not https") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(websiteUrl = "http://jbang.dev/"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject an uppercase candidate identifier") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(candidate = "JBang"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject an over-long description") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "d".repeat(2001)))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description carrying a trademark symbol") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "Apache Tomcat® software."))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description spanning two lines") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = """Java scripting.\nWithout a build."""))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("reject a description carrying consecutive spaces") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(description = "Java scripting,  without a build."))
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("answer 400 for malformed JSON") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"jbang",""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("answer a ValidationErrorResponse body for malformed JSON") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"jbang",""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    val body = Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText())
                    body.failures.map { it.field } shouldBe listOf("request")
                }
            }
        }

        should("answer 401 to an anonymous client") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to a vendor token") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("jbang")))
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("answer 401 to an expired token") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.expiredToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return exactly one Cache-Control value of no-store") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        should("return no Expires header") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.headers[HttpHeaders.Expires].toOption() shouldBe none()
                }
            }
        }
    })
