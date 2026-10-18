package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
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
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.configureJwtAuthentication
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.allowList
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase

@Tags("acceptance")
class SemverishValidationDisabledAcceptanceSpec :
    ShouldSpec({
        should("skip semverish validation for java when the flag is disabled") {
            withCleanDatabase {
                // given: an application booted with semverish validation toggled off
                val config = testApplicationConfig().apply { put("validation.semverish.enabled", "false") }
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

                        val candidateService = CandidateServiceImpl(PostgresCandidateRepository())

                        configureRouting(
                            versionService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional),
                            tagService = tagService,
                            healthRepo = PostgresHealthRepository(),
                            authService = authService,
                            vendorRepository = vendorRepo,
                            appConfig = appConfig,
                            versionRequestValidator = VersionRequestValidator(appConfig.semverishEnabled, allowList("java")),
                        )
                        configureCandidateRouting(candidateService, allowList("java"))
                    }

                    // when: posting a java version that violates the semverish grammar
                    val response =
                        client.post("/versions") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """
                                {
                                    "candidate": "java",
                                    "version": "25.0.2.fx",
                                    "platform": "LINUX_X64",
                                    "url": "https://example.com/java-25.0.2.fx.tar.gz"
                                }
                                """.trimIndent(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: the version is accepted
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }
    })
