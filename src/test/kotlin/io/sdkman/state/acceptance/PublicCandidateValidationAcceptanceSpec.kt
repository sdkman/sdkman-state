package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proves `POST /validate/candidates` answers as `POST /admin/candidates` would, without a login
 * and without registering anything, so a contributor can check a registration before an admin
 * applies it.
 */
@Tags("acceptance")
class PublicCandidateValidationAcceptanceSpec :
    ShouldSpec({
        fun registrationBody(websiteUrl: String = "https://jpx.example.com/"): String =
            """
            {"candidate":"jpx","name":"JPX","description":"A tool for jpx things.","website_url":"$websiteUrl"}
            """.trimIndent()

        fun String.listedCandidates(): List<String> =
            Json.decodeFromString<JsonArray>(this).map {
                it.jsonObject
                    .getValue("candidate")
                    .jsonPrimitive.content
            }

        should("validate a valid registration without a token") {
            withCleanDatabase {
                withTestApplication {
                    // when: anyone validates a valid registration body
                    val response =
                        client.post("/validate/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                        }

                    // then: it is accepted and never cached
                    response.status shouldBe HttpStatusCode.NoContent
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("not register the validated candidate") {
            withCleanDatabase {
                withTestApplication {
                    // given: anyone validates a registration for jpx
                    client.post("/validate/candidates") {
                        contentType(ContentType.Application.Json)
                        setBody(registrationBody())
                    }

                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: validation wrote nothing to the registry
                    response.bodyAsText().listedCandidates() shouldNotContain "jpx"
                }
            }
        }

        should("validate a valid registration even when a vendor token is sent") {
            withCleanDatabase {
                withTestApplication {
                    // when: a vendor, who may not register candidates, validates one
                    val response =
                        client.post("/validate/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("jpx")))
                        }

                    // then: the token is never inspected, so the admin role check does not apply
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("refuse a website_url that is not https with 400") {
            withCleanDatabase {
                withTestApplication {
                    // when: anyone validates a registration with a plain http website
                    val response =
                        client.post("/validate/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody(websiteUrl = "http://jpx.example.com/"))
                        }

                    // then: it is a validation failure, as on the write route, and never cached
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("still refuse POST /admin/candidates without a token") {
            withCleanDatabase {
                withTestApplication {
                    // when: a caller with no token posts to the write route
                    val response =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody())
                        }

                    // then: the write route still requires authentication
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
