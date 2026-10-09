package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class PublicCandidateValidationAcceptanceSpec :
    ShouldSpec({
        fun registrationBody(candidate: String = "jpx"): String =
            """
            {"candidate":"$candidate","name":"JPX","description":"Java packaging, simplified.","website_url":"https://jpx.example.com/"}
            """.trimIndent()

        fun String.listedCandidates(): List<String> =
            Json.decodeFromString<JsonArray>(this).map {
                it.jsonObject
                    .getValue("candidate")
                    .jsonPrimitive.content
            }

        suspend fun ApplicationTestBuilder.validate(
            body: String,
            token: Option<String> = none(),
        ): HttpResponse =
            client.post("/validate/candidates") {
                contentType(ContentType.Application.Json)
                setBody(body)
                token.onSome { bearerAuth(it) }
            }

        should("answer 204 to an anonymous valid body without registering the candidate") {
            withCleanDatabase {
                withTestApplication {
                    // when: anyone validates a registration of an unknown candidate
                    val response = validate(registrationBody())

                    // then: the write would be accepted, and nothing was registered
                    response.status shouldBe HttpStatusCode.NoContent
                    client.get("/candidates").bodyAsText().listedCandidates() shouldNotContain "jpx"
                }
            }
        }

        should("answer 204 to a valid body sent with a vendor token") {
            withCleanDatabase {
                withTestApplication {
                    // when: a vendor, refused by the admin-only write route, validates a registration
                    val response = validate(registrationBody(), JwtTestSupport.vendorToken(candidates = listOf("jpx")).some())

                    // then: validation predicts the body, never who may write it
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("answer 400 to an uppercase candidate identifier") {
            withCleanDatabase {
                withTestApplication {
                    val response = validate(registrationBody(candidate = "JPX"))

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("answer 413 with no-store to a body over 16384 bytes") {
            withCleanDatabase {
                withTestApplication {
                    val response = validate("x".repeat(16_385))

                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        mapOf(
            "an accepted body" to registrationBody(),
            "a rejected body" to registrationBody(candidate = "JPX"),
        ).forEach { (description, body) ->
            should("carry exactly one Cache-Control value no-store on $description") {
                withCleanDatabase {
                    withTestApplication {
                        val response = validate(body)

                        response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                    }
                }
            }
        }
    })
