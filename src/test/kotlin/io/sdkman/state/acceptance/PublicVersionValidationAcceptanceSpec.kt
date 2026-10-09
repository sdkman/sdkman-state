package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.unreadyAllowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class PublicVersionValidationAcceptanceSpec :
    ShouldSpec({
        val jpx =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://jpx.example.com/jpx-1.2.0-linux-x64.tar.gz",
                visible = true.some(),
            )

        suspend fun ApplicationTestBuilder.validate(
            body: String,
            token: Option<String> = none(),
        ): HttpResponse =
            client.post("/validate/versions") {
                contentType(ContentType.Application.Json)
                setBody(body)
                token.onSome { bearerAuth(it) }
            }

        should("answer 204 to an anonymous valid body without writing the version") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate
                    registerCandidates("jpx")

                    // when: anyone validates a version of it
                    val response = validate(jpx.toJsonString())

                    // then: the write would be accepted, and nothing was written
                    response.status shouldBe HttpStatusCode.NoContent
                    client.get("/versions/jpx/1.2.0?platform=LINUX_X64").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("answer 204 to a valid body sent with an expired token") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")

                    val response = validate(jpx.toJsonString(), JwtTestSupport.expiredToken().some())

                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("answer 400 to a version of an unregistered candidate") {
            withCleanDatabase {
                withTestApplication {
                    val response = validate(jpx.toJsonString())

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

        should("not answer 413 to a body of exactly 16384 bytes") {
            withCleanDatabase {
                withTestApplication {
                    registerCandidates("jpx")
                    val json = jpx.toJsonString()

                    val response = validate(json + " ".repeat(16_384 - json.length))

                    response.status shouldNotBe HttpStatusCode.PayloadTooLarge
                }
            }
        }

        should("answer 413 with no-store to a streamed body over 16384 bytes without a Content-Length") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/validate/versions") {
                            setBody(
                                object : OutgoingContent.WriteChannelContent() {
                                    override val contentType: ContentType = ContentType.Application.Json

                                    override suspend fun writeTo(channel: ByteWriteChannel) {
                                        channel.writeStringUtf8("x".repeat(16_385))
                                    }
                                },
                            )
                        }

                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }

        should("answer 500 when the registry has never loaded") {
            withCleanDatabase {
                withTestApplication(unreadyAllowList()) {
                    val response = validate(jpx.toJsonString())

                    response.status shouldBe HttpStatusCode.InternalServerError
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()) shouldBe
                        ErrorResponse("Internal Server Error", "Candidate registry unavailable")
                }
            }
        }

        mapOf(
            "an accepted body" to { jpx.toJsonString() },
            "a rejected body" to { jpx.copy(candidate = "gradle").toJsonString() },
        ).forEach { (description, body) ->
            should("carry exactly one Cache-Control value no-store on $description") {
                withCleanDatabase {
                    withTestApplication {
                        registerCandidates("jpx")

                        val response = validate(body())

                        response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                    }
                }
            }
        }

        should("carry exactly one Cache-Control value no-store when the registry has never loaded") {
            withCleanDatabase {
                withTestApplication(unreadyAllowList()) {
                    val response = validate(jpx.toJsonString())

                    response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("no-store")
                }
            }
        }
    })
