package io.sdkman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.support.extractTags
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication

private const val BASIC_AUTH_HEADER = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class PostVersionTagsApiSpec :
    ShouldSpec({

        should("POST version with tags stores and returns them in GET") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest", "27"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val postResponse =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, BASIC_AUTH_HEADER)
                        }
                    postResponse.status shouldBe HttpStatusCode.NoContent

                    val getResponse =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getResponse.status shouldBe HttpStatusCode.OK

                    getResponse.bodyAsText().extractTags() shouldBe listOf("latest", "27")
                }
            }
        }
    })
