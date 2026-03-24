package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.state.support.adminToken
import io.sdkman.state.support.extractTags
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class PostVersionTagsAcceptanceSpec :
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
                            header(Authorization, "Bearer ${adminToken()}")
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

        should("POST without tags field preserves existing tags") {
            val initialPost =
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

            val updatePost =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-updated-url.tar.gz"
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(initialPost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(updatePost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

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

        should("POST with empty tags clears all tags") {
            val initialPost =
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

            val clearTagsPost =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": []
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(initialPost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(clearTagsPost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    val getResponse =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getResponse.status shouldBe HttpStatusCode.OK

                    getResponse.bodyAsText().extractTags() shouldBe emptyList()
                }
            }
        }

        should("declarative replacement removes tags not in new list") {
            val initialPost =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest", "27", "lts"]
                }
                """.trimIndent()

            val replacePost =
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
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(initialPost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(replacePost)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

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

        should("mutual exclusivity moves tag from version A to version B") {
            val versionA =
                """
                {
                    "candidate": "java",
                    "version": "27.0.1",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.1-temurin-linux-x64.tar.gz",
                    "tags": ["latest"]
                }
                """.trimIndent()

            val versionB =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionA)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionB)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    val getA =
                        client.get("/versions/java/27.0.1") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getA.status shouldBe HttpStatusCode.OK
                    getA.bodyAsText().extractTags() shouldBe emptyList()

                    val getB =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getB.status shouldBe HttpStatusCode.OK
                    getB.bodyAsText().extractTags() shouldBe listOf("latest")
                }
            }
        }

        should("mutual exclusivity preserves other tags on source version") {
            val versionA =
                """
                {
                    "candidate": "java",
                    "version": "27.0.1",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.1-temurin-linux-x64.tar.gz",
                    "tags": ["latest", "27"]
                }
                """.trimIndent()

            val versionB =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionA)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionB)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    val getA =
                        client.get("/versions/java/27.0.1") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getA.status shouldBe HttpStatusCode.OK
                    getA.bodyAsText().extractTags() shouldBe listOf("27")

                    val getB =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getB.status shouldBe HttpStatusCode.OK
                    getB.bodyAsText().extractTags() shouldBe listOf("latest")
                }
            }
        }

        should("POST version with multiple tags stores all of them") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest", "27", "27.0", "lts"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    val getResponse =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getResponse.status shouldBe HttpStatusCode.OK

                    getResponse.bodyAsText().extractTags() shouldBe listOf("latest", "27", "27.0", "lts")
                }
            }
        }

        should("POST version without distribution stores tags with NA sentinel") {
            val requestBody =
                """
                {
                    "candidate": "gradle",
                    "version": "8.12",
                    "platform": "UNIVERSAL",
                    "url": "https://cdn.example.com/gradle-8.12.zip",
                    "tags": ["latest", "8"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    client
                        .post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }.status shouldBe HttpStatusCode.NoContent

                    val getResponse =
                        client.get("/versions/gradle/8.12") {
                            parameter("platform", "universal")
                        }
                    getResponse.status shouldBe HttpStatusCode.OK

                    getResponse.bodyAsText().extractTags() shouldBe listOf("latest", "8")
                }
            }
        }
        should("return 400 when tag contains invalid characters") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["inv@lid!"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[0]"
                    responseBody shouldContain "must contain only alphanumeric characters"
                }
            }
        }

        should("return 400 when tag is blank") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["   "]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[0]"
                    responseBody shouldContain "must not be blank"
                }
            }
        }

        should("return 400 when tag exceeds 50 characters") {
            val longTag = "a".repeat(51)
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["$longTag"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[0]"
                    responseBody shouldContain "must not exceed 50 characters"
                }
            }
        }

        should("return 400 when tag starts with a dot") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": [".hidden"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[0]"
                    responseBody shouldContain "must start and end with an alphanumeric character"
                }
            }
        }

        should("return 400 with accumulated errors for multiple invalid tags") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["   ", ".hidden"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[0]"
                    responseBody shouldContain "must not be blank"
                    responseBody shouldContain "tags[1]"
                    responseBody shouldContain "must start and end with an alphanumeric character"
                }
            }
        }

        should("return 400 and create no version when mix of valid and invalid tags") {
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "27.0.2",
                    "distribution": "TEMURIN",
                    "platform": "LINUX_X64",
                    "url": "https://cdn.example.com/java-27.0.2-temurin-linux-x64.tar.gz",
                    "tags": ["latest", "inv@lid!"]
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            header(Authorization, "Bearer ${adminToken()}")
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                    val responseBody = response.bodyAsText()
                    responseBody shouldContain "Validation failed"
                    responseBody shouldContain "tags[1]"

                    val getResponse =
                        client.get("/versions/java/27.0.2") {
                            parameter("platform", "linuxx64")
                            parameter("distribution", "TEMURIN")
                        }
                    getResponse.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    })
