package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.primary.rest.configureSerialization
import io.sdkman.state.adapter.secondary.persistence.ExposedTransactional
import io.sdkman.state.adapter.secondary.persistence.PostgresAuditRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresHealthRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresTagRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVendorRepository
import io.sdkman.state.adapter.secondary.persistence.PostgresVersionRepository
import io.sdkman.state.application.service.AuthServiceImpl
import io.sdkman.state.application.service.RateLimiter
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase

@Tags("acceptance")
class LoginRateLimitDisabledAcceptanceSpec :
    ShouldSpec({

        should("never return 429 when the rate limiter is disabled") {
            withCleanDatabase {
                // given: an application booted with the rate limiter toggled off
                val config = testApplicationConfig().apply { put("auth.rateLimit.enabled", "false") }
                val appConfig = DefaultAppConfig(config)
                testApplication {
                    environment {
                        this.config = config
                    }
                    application {
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

                        configureRouting(
                            versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
                            tagService = tagService,
                            healthRepo = PostgresHealthRepository(),
                            authService = authService,
                            vendorRepository = vendorRepo,
                            appConfig = appConfig,
                            versionRequestValidator = VersionRequestValidator(appConfig.semverishCandidates),
                        )
                    }

                    // when: far more than MAX_ATTEMPTS rapid logins from the same client
                    // then: each is authenticated on its merits (401 here) — never throttled to 429
                    repeat(10) {
                        client
                            .post("/login") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"email":"admin@sdkman.io","password":"wrong"}""")
                            }.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }
        }
    })
