package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class TokenValidationAcceptanceSpec :
    ShouldSpec({

        should("return 401 when no token is provided") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"java","version":"1.0","platform":"LINUX_X64","url":"https://x"}""")
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 when token is expired") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"java","version":"1.0","platform":"LINUX_X64","url":"https://x"}""")
                            bearerAuth(JwtTestSupport.expiredToken())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 when token has invalid signature") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"java","version":"1.0","platform":"LINUX_X64","url":"https://x"}""")
                            bearerAuth(JwtTestSupport.tokenWithWrongSecret())
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
