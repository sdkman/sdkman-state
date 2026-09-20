package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.sdkman.state.config.CandidateLoader
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class CandidateRegistryPublishingAcceptanceSpec :
    ShouldSpec({
        fun registrationBody(candidate: String): String =
            """
            {"candidate":"$candidate","name":"Nonesuch","description":"A candidate the allow-list does not name.","website_url":"https://nonesuch.example.com/"}
            """.trimIndent()

        should("accept a version for a candidate that the registry does not hold") {
            withCleanDatabase {
                withTestApplication {
                    // given: a clean database, so the registry holds no candidate at all
                    val version =
                        Version(
                            candidate = "gradle",
                            version = "8.14.3",
                            platform = Platform.UNIVERSAL,
                            url = "https://example.com/gradle-8.14.3.zip",
                            visible = true.some(),
                            distribution = none(),
                        )

                    // when: a vendor scoped to gradle publishes it
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(version.toJsonString())
                            bearerAuth(JwtTestSupport.vendorToken(candidates = listOf("gradle")))
                        }

                    // then: the publish path never consults the registry (rules 1 and 13)
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("refuse a version for a registered candidate that the allow-list omits") {
            withCleanDatabase {
                withTestApplication {
                    // given: nonesuch is registered, which the 400 below would pass vacuously without
                    val registration =
                        client.post("/admin/candidates") {
                            contentType(ContentType.Application.Json)
                            setBody(registrationBody("nonesuch"))
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    registration.status shouldBe HttpStatusCode.Created

                    val version =
                        Version(
                            candidate = "nonesuch",
                            version = "1.0.0",
                            platform = Platform.UNIVERSAL,
                            url = "https://example.com/nonesuch-1.0.0.zip",
                            visible = true.some(),
                            distribution = none(),
                        )

                    // when: an admin publishes a version for it
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(version.toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: a registry row authorises nothing; candidates.txt still decides
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("name gradle in the publish allow-list") {
            // then: the accepted publish above turns on the file, not on a registry row
            CandidateLoader.allowedCandidates shouldContain "gradle"
        }

        should("omit nonesuch from the publish allow-list") {
            // then: the refused publish above turns on the file, not on a missing registry row
            CandidateLoader.allowedCandidates shouldNotContain "nonesuch"
        }
    })
