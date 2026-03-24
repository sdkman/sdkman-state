package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.state.support.TEST_ADMIN_EMAIL
import io.sdkman.state.support.adminToken
import io.sdkman.state.support.vendorToken
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Tags("acceptance")
class AdminVendorManagementAcceptanceSpec :
    ShouldSpec({

        should("list vendors returns empty list") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.get("/admin/vendors") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldBe "[]"
                }
            }
        }

        should("create vendor returns generated password") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java", "kotlin"]}""")
                        }
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.bodyAsText()
                    body shouldContain "vendor@example.com"
                    body shouldContain "password"
                    body shouldContain "java"
                    body shouldContain "kotlin"
                }
            }
        }

        should("list vendors after create shows vendor without password") {
            withCleanDatabase {
                withTestApplication {
                    // given: create a vendor
                    client.post("/admin/vendors") {
                        contentType(ContentType.Application.Json)
                        header(Authorization, "Bearer ${adminToken()}")
                        setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                    }

                    // when: list vendors
                    val response =
                        client.get("/admin/vendors") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }

                    // then: vendor present without password
                    response.status shouldBe HttpStatusCode.OK
                    val body = response.bodyAsText()
                    body shouldContain "vendor@example.com"
                    val vendors = Json.parseToJsonElement(body).jsonArray
                    vendors.size shouldBe 1
                    vendors[0].jsonObject.containsKey("password") shouldBe false
                }
            }
        }

        should("update vendor (idempotent) returns new password") {
            withCleanDatabase {
                withTestApplication {
                    // given: create a vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    val firstPassword =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["password"]
                            ?.jsonPrimitive
                            ?.content

                    // when: update the vendor
                    val updateResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java", "kotlin"]}""")
                        }

                    // then: new password generated
                    updateResponse.status shouldBe HttpStatusCode.OK
                    val body = updateResponse.bodyAsText()
                    body shouldContain "kotlin"
                    val secondPassword =
                        Json
                            .parseToJsonElement(body)
                            .jsonObject["password"]
                            ?.jsonPrimitive
                            ?.content
                    (firstPassword != secondPassword) shouldBe true
                }
            }
        }

        should("login after password reset with updated candidates") {
            withCleanDatabase {
                withTestApplication {
                    // given: create vendor and get password
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java", "kotlin"]}""")
                        }
                    val password =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["password"]!!
                            .jsonPrimitive.content

                    // when: login with generated password
                    val loginResponse =
                        client.post("/admin/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "vendor@example.com", "password": "$password"}""")
                        }

                    // then: login succeeds
                    loginResponse.status shouldBe HttpStatusCode.OK
                    loginResponse.bodyAsText() shouldContain "token"
                }
            }
        }

        should("soft delete vendor returns deleted_at") {
            withCleanDatabase {
                withTestApplication {
                    // given: create a vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content

                    // when: soft delete
                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }

                    // then: deleted_at is set
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "deleted_at"
                    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    (json["deleted_at"]?.jsonPrimitive?.content != null) shouldBe true
                }
            }
        }

        should("list vendors after soft delete shows deleted_at non-null") {
            withCleanDatabase {
                withTestApplication {
                    // given: create and delete vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        header(Authorization, "Bearer ${adminToken()}")
                    }

                    // when: list vendors
                    val response =
                        client.get("/admin/vendors") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }

                    // then: deleted vendor present with deleted_at
                    response.status shouldBe HttpStatusCode.OK
                    val vendors = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                    vendors.size shouldBe 1
                    (vendors[0].jsonObject["deleted_at"]?.jsonPrimitive?.content != null) shouldBe true
                }
            }
        }

        should("resurrect soft-deleted vendor clears deleted_at with new password") {
            withCleanDatabase {
                withTestApplication {
                    // given: create and delete vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        header(Authorization, "Bearer ${adminToken()}")
                    }

                    // when: recreate the vendor
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java", "kotlin"]}""")
                        }

                    // then: vendor is resurrected
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldContain "password"
                    response.bodyAsText() shouldContain "kotlin"
                }
            }
        }

        should("return 401 for vendor management without token") {
            withCleanDatabase {
                withTestApplication {
                    val getResponse = client.get("/admin/vendors")
                    getResponse.status shouldBe HttpStatusCode.Unauthorized

                    val postResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    postResponse.status shouldBe HttpStatusCode.Unauthorized

                    val deleteResponse = client.delete("/admin/vendors/${UUID.randomUUID()}")
                    deleteResponse.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for vendor management with vendor token") {
            withCleanDatabase {
                withTestApplication {
                    val getResponse =
                        client.get("/admin/vendors") {
                            header(Authorization, "Bearer ${vendorToken()}")
                        }
                    getResponse.status shouldBe HttpStatusCode.Unauthorized

                    val postResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${vendorToken()}")
                            setBody("""{"email": "newvendor@example.com", "candidates": ["java"]}""")
                        }
                    postResponse.status shouldBe HttpStatusCode.Unauthorized

                    val deleteResponse =
                        client.delete("/admin/vendors/${UUID.randomUUID()}") {
                            header(Authorization, "Bearer ${vendorToken()}")
                        }
                    deleteResponse.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 400 for invalid email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "not-an-email", "candidates": ["java"]}""")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 for empty candidates") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": []}""")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 when email matches admin email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "$TEST_ADMIN_EMAIL", "candidates": ["java"]}""")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 404 for deleting non-existent vendor") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/admin/vendors/${UUID.randomUUID()}") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("return 404 for deleting already-deleted vendor") {
            withCleanDatabase {
                withTestApplication {
                    // given: create and delete vendor
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            header(Authorization, "Bearer ${adminToken()}")
                            setBody("""{"email": "vendor@example.com", "candidates": ["java"]}""")
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        header(Authorization, "Bearer ${adminToken()}")
                    }

                    // when: delete again
                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            header(Authorization, "Bearer ${adminToken()}")
                        }

                    // then: 404
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    })
