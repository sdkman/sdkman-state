package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.allowList
import io.sdkman.state.support.deleteJavaVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJavaRouteJsonString
import io.sdkman.state.support.toJavaUniqueVersionJsonString
import io.sdkman.state.support.unreadyAllowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class JavaRouteAccessAcceptanceSpec :
    ShouldSpec({
        val postedBuild = javaVersion(Distribution.TEMURIN, "25.0.5.0+3")
        val storedBuild = javaVersion(Distribution.TEMURIN, "25.0.3.0")
        val kotlinVendorToken = JwtTestSupport.vendorToken(candidates = listOf("kotlin"))
        val observerToken = JwtTestSupport.tokenWithClaims(role = "observer", candidates = listOf("java"))

        suspend fun withRegisteredJava(block: suspend ApplicationTestBuilder.() -> Unit) =
            withCleanDatabase {
                withTestApplication {
                    // given: java is registered, so the request succeeds for a caller allowed to send it
                    registerCandidates("java")
                    block()
                }
            }

        should("refuse a post from a vendor without java in scope with 403") {
            withRegisteredJava {
                // when: a vendor scoped to kotlin posts a valid java build
                val response = postJavaVersion(postedBuild, kotlinVendorToken)

                // then: the post is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse a post from the community role with 403") {
            withRegisteredJava {
                // when: the community role posts a valid java build
                val response = postJavaVersion(postedBuild, JwtTestSupport.communityToken())

                // then: the post is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse a post from an unknown role with java in its claims with 403") {
            withRegisteredJava {
                // when: an unknown role posts a valid java build
                val response = postJavaVersion(postedBuild, observerToken)

                // then: the post is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse a delete from a vendor without java in scope with 403") {
            withRegisteredJava {
                // given: an untagged java row
                insertVersions(storedBuild)

                // when: a vendor scoped to kotlin deletes it
                val response = deleteJavaVersion(storedBuild, kotlinVendorToken)

                // then: the delete is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse a delete from the community role with 403") {
            withRegisteredJava {
                // given: an untagged java row
                insertVersions(storedBuild)

                // when: the community role deletes it
                val response = deleteJavaVersion(storedBuild, JwtTestSupport.communityToken())

                // then: the delete is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse a delete from an unknown role with java in its claims with 403") {
            withRegisteredJava {
                // given: an untagged java row
                insertVersions(storedBuild)

                // when: an unknown role deletes it
                val response = deleteJavaVersion(storedBuild, observerToken)

                // then: the delete is forbidden
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("answer 401 to a post without a token") {
            withRegisteredJava {
                // when: an anonymous client posts a valid java build
                val response =
                    client.post("/versions/java") {
                        contentType(ContentType.Application.Json)
                        setBody(postedBuild.toJavaRouteJsonString())
                    }

                // then: the client is not authenticated
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        should("answer 401 to a delete without a token") {
            withRegisteredJava {
                // given: an untagged java row
                insertVersions(storedBuild)

                // when: an anonymous client deletes it
                val response =
                    client.delete("/versions/java") {
                        contentType(ContentType.Application.Json)
                        setBody(storedBuild.toJavaUniqueVersionJsonString())
                    }

                // then: the client is not authenticated
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        should("accept a post from a vendor with java in scope with 204") {
            withRegisteredJava {
                // when: DISCO posts a valid java build
                val response = postJavaVersion(postedBuild, javaVendorToken())

                // then: the build is stored
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        should("accept a post from the admin with 204") {
            withRegisteredJava {
                // when: the admin posts a valid java build
                val response = postJavaVersion(postedBuild, JwtTestSupport.adminToken())

                // then: the build is stored
                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        should("answer 400 with a request failure naming java when java is not registered") {
            withCleanDatabase {
                // given: a registry that holds gradle but not java
                withTestApplication(allowList("gradle")) {
                    // when: DISCO posts a valid java build
                    val response = postJavaVersion(postedBuild, javaVendorToken())

                    // then: the request failure names java
                    response.status shouldBe HttpStatusCode.BadRequest
                    Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText()) shouldBe
                        ValidationErrorResponse(
                            "Validation failed",
                            listOf(ValidationFailure("request", "Invalid request: candidate 'java' is not registered")),
                        )
                }
            }
        }

        should("answer 500 when the registry has never loaded") {
            withCleanDatabase {
                // given: a registry that has never loaded
                withTestApplication(unreadyAllowList()) {
                    // when: DISCO posts a valid java build
                    val response = postJavaVersion(postedBuild, javaVendorToken())

                    // then: the registry is reported unavailable
                    response.status shouldBe HttpStatusCode.InternalServerError
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()) shouldBe
                        ErrorResponse("Internal Server Error", "Candidate registry unavailable")
                }
            }
        }
    })
