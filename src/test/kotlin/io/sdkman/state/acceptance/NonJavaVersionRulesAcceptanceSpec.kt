package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class NonJavaVersionRulesAcceptanceSpec :
    ShouldSpec({
        val jpx =
            Version(
                candidate = "jpx",
                version = "1.2.0",
                platform = Platform.LINUX_X64,
                url = "https://jpx.example.com/jpx-1.2.0-linux-x64.tar.gz",
                visible = true.some(),
                distribution = none(),
            )

        suspend fun ApplicationTestBuilder.postVersion(
            body: String,
            token: String,
        ): HttpResponse =
            client.post("/versions") {
                contentType(ContentType.Application.Json)
                setBody(body)
                bearerAuth(token)
            }

        suspend fun HttpResponse.failedFields(): List<String> =
            Json.decodeFromString<ValidationErrorResponse>(bodyAsText()).failures.map { it.field }

        mapOf(
            "admin" to { JwtTestSupport.adminToken() },
            "vendor" to { JwtTestSupport.vendorToken(candidates = listOf("jpx")) },
            "community" to { JwtTestSupport.communityToken() },
        ).forEach { (caller, token) ->
            should("answer 400 on the version field when the $caller role posts jpx 1.2.0 LTS") {
                withCleanDatabase {
                    withTestApplication {
                        // given: a registered candidate
                        registerCandidates("jpx")

                        // when: the caller posts a version outside the grammar
                        val response = postVersion(jpx.copy(version = "1.2.0 LTS").toJsonString(), token())

                        // then: the version field is refused
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.failedFields() shouldBe listOf("version")
                    }
                }
            }
        }

        mapOf(
            "UNIVERSAL beside a LINUX_X64 row" to (Platform.LINUX_X64 to Platform.UNIVERSAL),
            "LINUX_X64 beside a UNIVERSAL row" to (Platform.UNIVERSAL to Platform.LINUX_X64),
        ).forEach { (case, platforms) ->
            val (storedPlatform, postedPlatform) = platforms
            should("answer 400 on the platform field when jpx 1.2.0 is posted for $case") {
                withCleanDatabase {
                    // given: jpx 1.2.0 holds one platform shape
                    insertVersions(jpx.copy(platform = storedPlatform))

                    withTestApplication {
                        registerCandidates("jpx")

                        // when: an admin posts the same version in the other shape
                        val response =
                            postVersion(jpx.copy(platform = postedPlatform).toJsonString(), JwtTestSupport.adminToken())

                        // then: the platform field is refused
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.failedFields() shouldBe listOf("platform")
                    }
                }
            }
        }

        should("answer 400 on the tags field when jpx 1.2.0 is posted with lts") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate
                    registerCandidates("jpx")

                    // when: an admin posts a version tagged lts
                    val response =
                        postVersion(jpx.copy(tags = listOf("lts").some()).toJsonString(), JwtTestSupport.adminToken())

                    // then: the tag is refused
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.failedFields() shouldBe listOf("tags[0]")
                }
            }
        }

        should("answer 400 on the tag field when POST /versions/tags assigns lts to jpx 1.2.0") {
            withCleanDatabase {
                // given: jpx 1.2.0 exists
                insertVersions(jpx)

                withTestApplication {
                    registerCandidates("jpx")

                    // when: an admin assigns lts through the tag route
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = "jpx",
                                    version = "1.2.0",
                                    distribution = none(),
                                    platform = Platform.LINUX_X64,
                                    tag = "lts",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the tag is refused
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.failedFields() shouldBe listOf("tag")
                }
            }
        }
    })
