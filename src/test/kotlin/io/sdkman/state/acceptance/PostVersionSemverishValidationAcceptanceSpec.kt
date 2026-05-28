package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

/**
 * Endpoint-level coverage of the semverish opt-in described in
 * `specs/semverish-version-validation.md`. The unit-level
 * [io.sdkman.state.application.validation.SemverishVersionValidatorSpec]
 * exhaustively covers the grammar; this spec exercises the *wiring* — that
 * `POST /versions` actually enforces the rule for opted-in candidates and
 * leaves other candidates alone. One representative valid and one
 * representative invalid example is enough at this layer; the spec's
 * Gherkin scenarios are the contract being verified.
 *
 * The default test config sets `validation.semverish.candidates = "java"`
 * (see `support/Application.kt`), so these tests use `java` for the opted-in
 * scenarios and `scala` for the not-opted-in scenario without needing a
 * per-spec config override.
 */
@Tags("acceptance")
class PostVersionSemverishValidationAcceptanceSpec :
    ShouldSpec({

        should("accept a conforming version for an opted-in candidate (java)") {
            // given: a semverish-conforming version for the opted-in candidate
            val version =
                Version(
                    candidate = "java",
                    version = "25.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://example.com/java-25.0.2.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    // when: the client POSTs the version
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the request is accepted (current success status is 204)
                    response.status shouldBe HttpStatusCode.NoContent
                }
                // and: the row exists exactly as posted
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }

        should("reject a non-conforming version for an opted-in candidate (java) with a semverish failure") {
            // given: a non-conforming version for the opted-in candidate — bare major,
            // missing minor and patch (first invalid example in the spec's grammar list)
            val invalidVersion = "26"
            val requestBody =
                """
                {
                    "candidate": "java",
                    "version": "$invalidVersion",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-26.tar.gz"
                }
                """.trimIndent()

            withCleanDatabase {
                withTestApplication {
                    // when: the client POSTs the malformed version
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the request is rejected with 400 and a validation-error payload
                    // that points the client at the `version` field and names the format violation
                    response.status shouldBe HttpStatusCode.BadRequest
                    val payload = Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText())
                    payload.error shouldBe "Validation failed"
                    val semverishFailure = payload.failures.single { it.field == "version" }
                    semverishFailure.message shouldContain "does not conform to the semverish format"
                    semverishFailure.message shouldContain invalidVersion
                }
            }
        }

        should("accept a non-conforming version for a candidate that has NOT opted in (scala)") {
            // given: a non-opted-in candidate (scala is absent from the default
            // `validation.semverish.candidates` set) with a version that would
            // fail semverish. The pre-feature contract must be preserved here:
            // candidates outside the opt-in set are unaffected.
            val version =
                Version(
                    candidate = "scala",
                    version = "26",
                    platform = Platform.UNIVERSAL,
                    url = "https://example.com/scala-26.tar.gz",
                    visible = true.some(),
                )
            val requestBody = version.toJsonString()

            withCleanDatabase {
                withTestApplication {
                    // when: the client POSTs the would-be-invalid version
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the request is accepted because the candidate is not opted in
                    response.status shouldBe HttpStatusCode.NoContent
                }
                // and: the row is stored verbatim — no normalisation has occurred
                selectVersion(
                    candidate = version.candidate,
                    version = version.version,
                    distribution = version.distribution,
                    platform = version.platform,
                ) shouldBe version.some()
            }
        }
    })
