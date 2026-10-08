package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
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
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.allowList
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.unreadyAllowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

/**
 * Proves `POST /validate/versions` answers as `POST /versions` would, without a login and without
 * writing, so a contributor can check a version before anyone with publish rights sees it.
 *
 * The registry is an in-memory [allowList] with no `candidates` row behind it: a `204` for a
 * candidate only the allow-list knows shows validation reads no database per request.
 */
@Tags("acceptance")
class PublicVersionValidationAcceptanceSpec :
    ShouldSpec({
        val jpx =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://jpx.example.com/jpx-1.2.0-linux-x64.zip",
                visible = true.some(),
            )

        should("validate a valid version without a token and without writing it") {
            withCleanDatabase {
                withTestApplication(allowList("jpx")) {
                    // when: anyone validates a valid version body
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                        }

                    // then: it is accepted, never cached, and nothing is written
                    response.status shouldBe HttpStatusCode.NoContent
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    client.get("/versions/jpx/1.2.0?platform=LINUX_X64").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("validate a valid version even when the sent token is invalid") {
            withCleanDatabase {
                withTestApplication(allowList("jpx")) {
                    // when: a caller validates with a token signed by the wrong secret
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                            bearerAuth(JwtTestSupport.tokenWithWrongSecret())
                        }

                    // then: the token is never inspected, so it cannot cause a 401
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("refuse a version of a candidate absent from the registry with 400") {
            withCleanDatabase {
                withTestApplication(allowList("gradle")) {
                    // when: anyone validates a version of an unregistered candidate
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                        }

                    // then: it is a validation failure, as on the write route
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("answer 500 when the registry has never loaded") {
            withCleanDatabase {
                withTestApplication(unreadyAllowList()) {
                    // when: anyone validates while the registry is unready
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                        }

                    // then: the transient fault is reported as such, never as a 400
                    response.status shouldBe HttpStatusCode.InternalServerError
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()) shouldBe
                        ErrorResponse("Internal Server Error", "Candidate registry unavailable")
                }
            }
        }

        should("validate against the in-memory registry without reading the candidates table") {
            withCleanDatabase {
                // given: jpx is in the allow-list but has no candidates row
                withTestApplication(allowList("jpx")) {
                    // when: anyone validates a jpx version
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                        }

                    // then: it is accepted, because validation reads the allow-list alone
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("still refuse POST /versions without a token") {
            withCleanDatabase {
                withTestApplication(allowList("jpx")) {
                    // when: a caller with no token posts to the write route
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(jpx.toJsonString())
                        }

                    // then: the write route still requires authentication
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
