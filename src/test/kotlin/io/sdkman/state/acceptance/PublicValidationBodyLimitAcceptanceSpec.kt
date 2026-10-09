package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.adapter.primary.rest.VALIDATION_BODY_LIMIT_BYTES
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.allowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class PublicValidationBodyLimitAcceptanceSpec :
    ShouldSpec({
        val oversizedBody = "x".repeat((VALIDATION_BODY_LIMIT_BYTES + 1).toInt())

        should("refuse an oversized body on POST /validate/versions with 413") {
            withCleanDatabase {
                withTestApplication(allowList("jpx")) {
                    // when: anyone validates a body one byte over the limit
                    val response =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(oversizedBody)
                        }

                    // then: it is refused unread, and the refusal is never cached
                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("refuse an oversized body on POST /validate/candidates with 413") {
            withCleanDatabase {
                withTestApplication {
                    // when: anyone validates a registration body one byte over the limit
                    val response =
                        client.post("/validate/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(oversizedBody)
                        }

                    // then: it is refused unread, and the refusal is never cached
                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("not cap the body of POST /versions") {
            withCleanDatabase {
                withTestApplication(allowList("jpx")) {
                    // when: an admin posts the same oversized body to the write route
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(oversizedBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the write route reads it and answers on its content, not its size
                    response.status shouldNotBe HttpStatusCode.PayloadTooLarge
                }
            }
        }
    })
