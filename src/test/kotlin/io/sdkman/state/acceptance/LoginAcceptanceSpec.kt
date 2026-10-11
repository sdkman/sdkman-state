package io.sdkman.state.acceptance

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVendor
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class LoginAcceptanceSpec :
    ShouldSpec({

        should("return 200 with JWT for valid admin credentials") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","password":"testadminpassword"}""")
                        }

                    response.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body
                        .getValue("token")
                        .jsonPrimitive.content
                        .shouldNotBeBlank()
                }
            }
        }

        should("return 200 with JWT for valid vendor credentials") {
            withCleanDatabase {
                withTestApplication {
                    // given: create a vendor first
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"vendor@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    createResponse.status shouldBe HttpStatusCode.Created
                    val vendorBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                    val vendorPassword = vendorBody.getValue("password").jsonPrimitive.content

                    // when: login with vendor credentials
                    val loginResponse =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"vendor@test.com","password":"$vendorPassword"}""")
                        }

                    // then
                    loginResponse.status shouldBe HttpStatusCode.OK
                    val loginBody = Json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
                    loginBody
                        .getValue("token")
                        .jsonPrimitive.content
                        .shouldNotBeBlank()
                }
            }
        }

        should("return 200 for a vendor whose password was hashed at cost 12") {
            withCleanDatabase {
                // given: a vendor stored with a cost-12 hash while the service runs at cost 4
                val password = "cost12-password"
                val hashedPassword = String(BCrypt.withDefaults().hash(12, password.toByteArray()))
                insertVendor("cost12@test.com", hashedPassword, listOf("java"))

                withTestApplication {
                    // when
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"cost12@test.com","password":"$password"}""")
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("return 200 with a community-role JWT for valid community credentials") {
            withCleanDatabase {
                withTestApplication {
                    // when
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"email":"${JwtTestSupport.COMMUNITY_EMAIL}","password":"${JwtTestSupport.COMMUNITY_PASSWORD}"}""",
                            )
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val token =
                        Json
                            .parseToJsonElement(response.bodyAsText())
                            .jsonObject
                            .getValue("token")
                            .jsonPrimitive.content
                    JWT.decode(token).getClaim("role").asString() shouldBe "community"
                }
            }
        }

        should("return 401 for wrong password") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","password":"wrong"}""")
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for non-existent email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"unknown@test.com","password":"any"}""")
                        }

                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for soft-deleted vendor") {
            withCleanDatabase {
                withTestApplication {
                    // given: create and delete a vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"deleted@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
                    val vendorId = vendorBody.getValue("id").jsonPrimitive.content
                    val vendorPassword = vendorBody.getValue("password").jsonPrimitive.content

                    client.delete("/admin/vendors/$vendorId") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val loginResponse =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"deleted@test.com","password":"$vendorPassword"}""")
                        }

                    // then
                    loginResponse.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 429 when rate limit exceeded") {
            withCleanDatabase {
                withTestApplication {
                    // given: exhaust rate limit with 5 attempts
                    repeat(5) {
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","password":"wrong"}""")
                        }
                    }

                    // when: 6th attempt
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","password":"wrong"}""")
                        }

                    // then
                    response.status shouldBe HttpStatusCode.TooManyRequests
                }
            }
        }
    })
