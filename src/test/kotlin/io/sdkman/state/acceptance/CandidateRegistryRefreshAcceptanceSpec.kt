package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.sdkman.state.adapter.secondary.persistence.PostgresCandidateRepository
import io.sdkman.state.application.service.RefreshingCandidateAllowList
import io.sdkman.state.config.scheduleCandidateRefresh
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.configureTestApplication
import io.sdkman.state.support.insertCandidates
import io.sdkman.state.support.sharedTestDatabase
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import kotlin.time.Duration.Companion.seconds

/**
 * Proves the periodic refresh of rule 3: a candidate that appears in the table without this
 * instance serving the write becomes publishable within the interval, with no restart.
 *
 * The registration is inserted straight into the table rather than posted to `POST /admin/candidates`,
 * because the admin route refreshes the holder eagerly and would prove the write refresh instead.
 * The row therefore stands in for a registration served by a sibling instance, which is the only
 * case the periodic refresh exists to cover.
 */
@Tags("acceptance")
class CandidateRegistryRefreshAcceptanceSpec :
    ShouldSpec({
        should("accept a version of a candidate another instance registered, once the refresh interval elapses") {
            withCleanDatabase {
                sharedTestDatabase
                testApplication {
                    environment {
                        config = testApplicationConfig()
                    }
                    application {
                        val candidateAllowList = RefreshingCandidateAllowList(PostgresCandidateRepository())
                        configureTestApplication(candidateAllowList)
                        scheduleCandidateRefresh(candidateAllowList, intervalMs = 200)
                    }

                    val body =
                        Version(
                            candidate = "groovy",
                            version = "4.0.0",
                            platform = Platform.UNIVERSAL,
                            url = "https://groovy.example.com/groovy-4.0.0.zip",
                            visible = true.some(),
                        ).toJsonString()

                    // given: the startup load has read an empty registry, so groovy is rejected
                    eventually(2.seconds) {
                        client
                            .post("/versions") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                                bearerAuth(JwtTestSupport.adminToken())
                            }.status shouldBe HttpStatusCode.BadRequest
                    }

                    // when: a registration lands in the table without any admin write to this instance
                    insertCandidates(
                        CandidateRegistration(
                            candidate = "groovy",
                            name = "Groovy",
                            description = "The Groovy candidate, registered by a sibling instance.",
                            websiteUrl = "https://groovy.example.com/",
                        ),
                    )

                    // then: the periodic refresh grants publishing rights without a restart
                    eventually(2.seconds) {
                        client
                            .post("/versions") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                                bearerAuth(JwtTestSupport.adminToken())
                            }.status shouldBe HttpStatusCode.NoContent
                    }
                }
            }
        }
    })
