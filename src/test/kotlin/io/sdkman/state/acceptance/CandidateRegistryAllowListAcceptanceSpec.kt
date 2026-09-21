package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

/**
 * Proves the registry is the allow-list: registering a candidate grants publishing rights and
 * deleting one revokes them, both on the instance that served the write and without a restart.
 *
 * This is the repair the cutover exists to make — `jpx` fell out of the classpath allow-list and
 * needed a deploy to come back. The delete-versus-publish race of rule 6 is deliberately not
 * covered here; it is accepted rather than closed.
 */
@Tags("acceptance")
class CandidateRegistryAllowListAcceptanceSpec :
    ShouldSpec({
        fun versionOf(candidate: String): Version =
            Version(
                candidate = candidate,
                version = "1.0.0",
                platform = Platform.UNIVERSAL,
                url = "https://$candidate.example.com/$candidate-1.0.0.zip",
                visible = true.some(),
            )

        should("accept a version of a candidate registered through the admin route, without a restart") {
            withCleanDatabase {
                withTestApplication {
                    // given: the candidate is not registered, so publishing to it is rejected
                    val rejected =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionOf("jpx").toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    rejected.status shouldBe HttpStatusCode.BadRequest

                    // when: an admin registers it
                    registerCandidates("jpx")

                    // then: the same publish succeeds against the same running instance
                    val accepted =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionOf("jpx").toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    accepted.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        should("reject a version of a candidate the admin route deleted, without a restart") {
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate with no versions
                    registerCandidates("jbang")

                    // when: an admin deletes it
                    client.delete("/admin/candidates/jbang") {
                        bearerAuth(JwtTestSupport.adminToken())
                    }

                    // then: the instance that served the deletion stops accepting publishes to it
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(versionOf("jbang").toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    })
