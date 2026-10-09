package io.sdkman.state.acceptance

import com.auth0.jwt.JWT
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class CommunityLoginAcceptanceSpec :
    ShouldSpec({
        should("return 200 with a community token for valid community credentials") {
            withCleanDatabase {
                // given: an application booted with a community account configured
                val config =
                    testApplicationConfig().apply {
                        put("community.email", JwtTestSupport.COMMUNITY_EMAIL)
                        put("community.password", JwtTestSupport.COMMUNITY_PASSWORD)
                    }
                withTestApplication(config) {
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
    })
