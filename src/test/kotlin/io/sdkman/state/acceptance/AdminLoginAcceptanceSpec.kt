package io.sdkman.state.acceptance

import at.favre.lib.crypto.bcrypt.BCrypt
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.support.TEST_ADMIN_EMAIL
import io.sdkman.state.support.TEST_ADMIN_PASSWORD
import io.sdkman.state.support.insertVendor
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import java.time.Instant

@Tags("acceptance")
class AdminLoginAcceptanceSpec :
    ShouldSpec({

        should("return JWT with role admin for valid admin credentials") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "$TEST_ADMIN_EMAIL", "password": "$TEST_ADMIN_PASSWORD"}""")
                        }
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "token"
                }
            }
        }

        should("return JWT with role vendor for valid vendor credentials") {
            val password = "vendor-password-123"
            val hashedPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray())

            withCleanDatabase {
                insertVendor(
                    email = "vendor@example.com",
                    password = hashedPassword,
                    candidates = listOf("java", "kotlin"),
                )
                withTestApplication {
                    val response =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "vendor@example.com", "password": "$password"}""")
                        }
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "token"
                }
            }
        }

        should("return 401 for wrong password") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "$TEST_ADMIN_EMAIL", "password": "wrong-password"}""")
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for non-existent email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "nobody@example.com", "password": "whatever"}""")
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for soft-deleted vendor") {
            val password = "vendor-password-123"
            val hashedPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray())

            withCleanDatabase {
                insertVendor(
                    email = "deleted@example.com",
                    password = hashedPassword,
                    candidates = listOf("java"),
                    deletedAt = Instant.now(),
                )
                withTestApplication {
                    val response =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "deleted@example.com", "password": "$password"}""")
                        }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    })
