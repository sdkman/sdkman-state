package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class JavaGenericRouteRefusalAcceptanceSpec :
    ShouldSpec({
        val postedBuild = javaVersion(Distribution.TEMURIN, "25.0.5.0+3")
        val storedBuild = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
        val gradleVendorToken = JwtTestSupport.vendorToken(candidates = listOf("gradle"))

        fun gradleVersion(version: String): Version =
            Version(
                candidate = "gradle",
                version = version,
                platform = Platform.UNIVERSAL,
                url = "https://services.gradle.org/distributions/gradle-$version-bin.zip",
                visible = true.some(),
            )

        fun uniqueVersionOf(version: Version): String =
            UniqueVersion(version.candidate, version.version, version.distribution, version.platform).toJsonString()

        suspend fun ApplicationTestBuilder.postVersion(
            route: String,
            body: String,
            token: String,
        ): HttpResponse =
            client.post(route) {
                contentType(ContentType.Application.Json)
                setBody(body)
                bearerAuth(token)
            }

        suspend fun ApplicationTestBuilder.deleteVersion(
            body: String,
            token: String,
        ): HttpResponse =
            client.delete("/versions") {
                contentType(ContentType.Application.Json)
                setBody(body)
                bearerAuth(token)
            }

        should("refuse the admin's POST /versions for java with 400 identifying candidate and naming POST /versions/java") {
            withCleanDatabase {
                withTestApplication {
                    // given: java is registered, so the registry does not refuse the post
                    registerCandidates("java")

                    // when: the admin posts a java build to the generic route
                    val response = postVersion("/versions", postedBuild.toJsonString(), JwtTestSupport.adminToken())

                    // then: the only failure points at the java route
                    response.status shouldBe HttpStatusCode.BadRequest
                    Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText()) shouldBe
                        ValidationErrorResponse(
                            error = "Validation failed",
                            failures =
                                listOf(
                                    ValidationFailure("candidate", "candidate 'java' is not accepted here; use POST /versions/java"),
                                ),
                        )
                }
            }
        }

        should("refuse DISCO's POST /versions for java with 400 and store nothing") {
            withCleanDatabase {
                withTestApplication {
                    // given: java is registered
                    registerCandidates("java")

                    // when: DISCO, with java in scope, posts a java build to the generic route
                    val response = postVersion("/versions", postedBuild.toJsonString(), javaVendorToken())

                    // then: the post is refused
                    response.status shouldBe HttpStatusCode.BadRequest
                }

                // and: no row is stored
                visibilityOf(postedBuild) shouldBe none()
            }
        }

        should("answer POST /validate/versions for java with the same 400 as POST /versions") {
            withCleanDatabase {
                withTestApplication {
                    // given: java is registered
                    registerCandidates("java")

                    // when: the admin writes a java build, and anyone validates it
                    val written = postVersion("/versions", postedBuild.toJsonString(), JwtTestSupport.adminToken())
                    val validated =
                        client.post("/validate/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(postedBuild.toJsonString())
                        }

                    // then: the twin refuses it with the identical answer
                    validated.status shouldBe HttpStatusCode.BadRequest
                    validated.bodyAsText() shouldBe written.bodyAsText()
                }
            }
        }

        should("refuse the admin's DELETE /versions for java with 400 naming DELETE /versions/java and keep the row") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is stored and visible
                insertVersions(storedBuild)

                withTestApplication {
                    // when: the admin deletes it through the generic route
                    val response = deleteVersion(uniqueVersionOf(storedBuild), JwtTestSupport.adminToken())

                    // then: the delete points at the java route
                    response.status shouldBe HttpStatusCode.BadRequest
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()) shouldBe
                        ErrorResponse("Bad Request", "candidate 'java' is not accepted here; use DELETE /versions/java")
                }

                // and: the row is still stored and visible
                visibilityOf(storedBuild) shouldBe true.some()
            }
        }

        should("refuse DELETE /versions for java with 400 rather than 403 to a vendor without java in scope") {
            withCleanDatabase {
                // given: TEMURIN 25.0.4.1+1 is stored
                insertVersions(storedBuild)

                withTestApplication {
                    // when: a vendor scoped to gradle deletes it through the generic route
                    val response = deleteVersion(uniqueVersionOf(storedBuild), gradleVendorToken)

                    // then: the route refuses java before it checks the caller
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        should("let a gradle vendor post gradle 8.14 to POST /versions with 204, leaving gradle 8.13 visible") {
            val previousRelease = gradleVersion("8.13")
            val newRelease = gradleVersion("8.14")

            withCleanDatabase {
                // given: gradle 8.13 on UNIVERSAL is visible
                insertVersions(previousRelease)

                withTestApplication {
                    registerCandidates("gradle")

                    // when: a vendor with gradle in scope posts gradle 8.14
                    val response = postVersion("/versions", newRelease.toJsonString(), gradleVendorToken)

                    // then: the post is stored
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: gradle 8.13 is still visible
                visibilityOf(previousRelease) shouldBe true.some()
            }
        }

        should("accept scala 3.0.0-beta-1 on POST /versions with 204, applying no version grammar") {
            val betaRelease =
                Version(
                    candidate = "scala",
                    version = "3.0.0-beta-1",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/scala-3.0.0-beta-1.tar.gz",
                    visible = true.some(),
                )

            withCleanDatabase {
                withTestApplication {
                    // given: scala is registered
                    registerCandidates("scala")

                    // when: the admin posts a scala version outside the java grammar
                    val response = postVersion("/versions", betaRelease.toJsonString(), JwtTestSupport.adminToken())

                    // then: the post is stored
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }
    })
