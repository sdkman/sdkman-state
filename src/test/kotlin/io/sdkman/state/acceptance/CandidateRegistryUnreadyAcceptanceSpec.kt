package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.parseJsonObject
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.unreadyAllowList
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

/**
 * Proves rule 5: a registry that has never loaded is not an empty registry.
 *
 * A cold-load failure must reach the publisher as a `500`, because a `400` enumerating an empty
 * allow-list reads as "this candidate is not valid", which is permanent to a retrying client while
 * the real fault is transient. Reads never consult the registry, so they keep serving throughout —
 * which is why an unready holder degrades publishing alone rather than failing the health check.
 */
@Tags("acceptance")
class CandidateRegistryUnreadyAcceptanceSpec :
    ShouldSpec({
        val gradle =
            Version(
                candidate = "gradle",
                version = "8.10",
                platform = Platform.UNIVERSAL,
                url = "https://gradle.example.com/gradle-8.10.zip",
                visible = true.some(),
            )

        should("serve a version read while the registry has never loaded") {
            withCleanDatabase {
                // given: a version row and a registry that never loaded
                insertVersions(gradle)
                withTestApplication(unreadyAllowList()) {
                    // when: a client reads the candidate's versions
                    val response = client.get("/versions/gradle")

                    // then: the read path is unaffected, because it never consults the registry
                    response.status shouldBe HttpStatusCode.OK
                }
            }
        }

        should("answer 500 rather than 400 when publishing while the registry has never loaded") {
            withCleanDatabase {
                withTestApplication(unreadyAllowList()) {
                    // when: a vendor publishes a version of a candidate
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(gradle.toJsonString())
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the unready registry is reported as a server fault
                    response.status shouldBe HttpStatusCode.InternalServerError
                    val body = response.bodyAsText()
                    Json.decodeFromString<ErrorResponse>(body) shouldBe
                        ErrorResponse("Internal Server Error", "Candidate registry unavailable")
                    withClue("an unready registry is never reported as a validation failure") {
                        body.parseJsonObject() shouldNotContainKey "failures"
                    }
                }
            }
        }
    })
