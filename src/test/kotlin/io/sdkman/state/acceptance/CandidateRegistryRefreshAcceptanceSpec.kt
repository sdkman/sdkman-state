package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.sdkman.state.adapter.primary.rest.configureCandidateRouting
import io.sdkman.state.adapter.primary.rest.configureHTTP
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.secondary.persistence.ExposedTransactional
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresCandidateRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.CandidateServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.RefreshingCandidateAllowList
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.config.scheduleCandidateRefresh
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
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
                val config = testApplicationConfig()
                val appConfig = DefaultAppConfig(config)
                testApplication {
                    environment {
                        this.config = config
                    }
                    application {
                        configureHTTP()
                        configureSerialization()
                        configureJwtAuthentication(appConfig)

                        val versionsRepo = PostgresVersionRepository()
                        val tagsRepo = PostgresTagRepository()
                        val auditRepo = PostgresAuditRepository()
                        val vendorRepo = PostgresVendorRepository()
                        val tagService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo)
                        val transactional = ExposedTransactional()
                        val rateLimiter = RateLimiter(appConfig.rateLimitEnabled)
                        val authService = AuthServiceImpl(vendorRepo, appConfig, rateLimiter)

                        val candidateRepo = PostgresCandidateRepository()
                        val candidateAllowList = RefreshingCandidateAllowList(candidateRepo)
                        scheduleCandidateRefresh(candidateAllowList, intervalMs = 200)

                        configureRouting(
                            versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
                            tagService = tagService,
                            healthRepo = PostgresHealthRepository(),
                            authService = authService,
                            vendorRepository = vendorRepo,
                            appConfig = appConfig,
                            versionRequestValidator =
                                VersionRequestValidator(appConfig.semverishCandidates, candidateAllowList),
                        )
                        configureCandidateRouting(CandidateServiceImpl(candidateRepo), candidateAllowList)
                    }

                    // given: the application is running, so its first refresh has read an empty registry
                    client.get("/meta/health")

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
                    val body =
                        Version(
                            candidate = "groovy",
                            version = "4.0.0",
                            platform = Platform.UNIVERSAL,
                            url = "https://groovy.example.com/groovy-4.0.0.zip",
                            visible = true.some(),
                        ).toJsonString()

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
