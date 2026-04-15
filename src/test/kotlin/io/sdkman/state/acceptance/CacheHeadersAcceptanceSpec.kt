package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class CacheHeadersAcceptanceSpec :
    ShouldSpec({

        val javaVersion =
            Version(
                candidate = "java",
                version = "17.0.1",
                distribution = Distribution.TEMURIN.some(),
                platform = Platform.LINUX_X64,
                url = "https://java-17.0.1-tem",
                visible = true.some(),
                tags = emptyList<String>().some(),
            )

        should("return Cache-Control max-age on GET /versions/{candidate}") {
            withCleanDatabase {
                insertVersions(javaVersion)
                withTestApplication {
                    val response = client.get("/versions/java")

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "max-age=600"
                }
            }
        }

        should("return Cache-Control max-age on GET /versions/{candidate}/{version}") {
            withCleanDatabase {
                insertVersions(javaVersion)
                withTestApplication {
                    val response =
                        client.get("/versions/java/17.0.1") {
                            url {
                                parameters.append("platform", "linuxx64")
                                parameters.append("distribution", "TEMURIN")
                            }
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "max-age=600"
                }
            }
        }

        should("return Cache-Control no-store on GET /meta/health") {
            withCleanDatabase {
                withTestApplication {
                    val response = client.get("/meta/health")

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("return Cache-Control no-store on POST /login") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"email":"admin@sdkman.io","password":"testadminpassword"}""")
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }

        should("return Cache-Control no-store on GET /admin/vendors") {
            withCleanDatabase {
                withTestApplication {
                    val response =
                        client.get("/admin/vendors") {
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                }
            }
        }
    })
