package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class AdminVendorManagementAcceptanceSpec :
    ShouldSpec({

        should("list vendors returns empty list") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldBe "[]"
                }
            }
        }

        should("create vendor returns 201 with generated password") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"vendor@test.com","candidates":["java","kotlin"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.Created
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["email"]!!.jsonPrimitive.content shouldBe "vendor@test.com"
                    body["password"]!!.jsonPrimitive.content.shouldNotBeBlank()
                    body["candidates"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("java", "kotlin")
                }
            }
        }

        should("list vendors after create returns vendor without password") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    client.post("/admin/vendors") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"list@test.com","candidates":["java"]}""")
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val vendors = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                    vendors.size shouldBe 1
                    val vendor = vendors[0].jsonObject
                    vendor["email"]!!.jsonPrimitive.content shouldBe "list@test.com"
                    vendor.containsKey("password") shouldBe false
                }
            }
        }

        should("update existing vendor returns 200 with regenerated password") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    client.post("/admin/vendors") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"update@test.com","candidates":["java"]}""")
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"update@test.com","candidates":["kotlin","groovy"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["candidates"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                        listOf("kotlin", "groovy")
                    body["password"]!!.jsonPrimitive.content.shouldNotBeBlank()
                }
            }
        }

        should("soft delete vendor returns 200 with deleted_at") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"delete@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content

                    // when
                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body.containsKey("deleted_at") shouldBe true
                }
            }
        }

        should("list vendors with include_deleted includes deleted vendor") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"del-list@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val response =
                        client.get("/admin/vendors?include_deleted=true") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val vendors = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                    vendors.size shouldBe 1
                }
            }
        }

        should("list vendors without filter excludes deleted vendor") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"excl@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldBe "[]"
                }
            }
        }

        should("resurrect soft-deleted vendor via POST returns 200") {
            withCleanDatabase {
                withTestApplication {
                    // given: create and delete
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"resurrect@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when: re-create
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"resurrect@test.com","candidates":["kotlin"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    body["password"]!!.jsonPrimitive.content.shouldNotBeBlank()
                }
            }
        }

        should("return 401 for admin endpoints without token") {
            withCleanDatabase {
                withTestApplication {
                    client.get("/admin/vendors").status shouldBe HttpStatusCode.Unauthorized
                    client
                        .post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"x@test.com","candidates":["java"]}""")
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 401 for admin endpoints with vendor token") {
            withCleanDatabase {
                withTestApplication {
                    val vendorToken = JwtTestSupport.vendorToken(candidates = listOf("java"))

                    client
                        .get("/admin/vendors") {
                            bearerAuth(vendorToken)
                        }.status shouldBe HttpStatusCode.Unauthorized

                    client
                        .post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"x@test.com","candidates":["java"]}""")
                            bearerAuth(vendorToken)
                        }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        should("return 400 for invalid email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"not-an-email","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 for empty candidates list") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"vendor@test.com","candidates":[]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 400 for admin email") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("return 404 for deleting non-existent vendor") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.delete("/admin/vendors/00000000-0000-0000-0000-000000000001") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("return 404 for deleting already-deleted vendor") {
            withCleanDatabase {
                withTestApplication {
                    // given
                    val createResponse =
                        client.post("/admin/vendors") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"deldel@test.com","candidates":["java"]}""")
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    val vendorId =
                        Json
                            .parseToJsonElement(createResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content
                    client.delete("/admin/vendors/$vendorId") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // when
                    val response =
                        client.delete("/admin/vendors/$vendorId") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    })
