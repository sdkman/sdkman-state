package io.sdkman.state.acceptance

import com.auth0.jwt.JWT
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class CommunityLoginAcceptanceSpec :
    ShouldSpec({
        should("return 200 with a community token for valid community credentials") {
            withCleanDatabase {
                // given: an application booted with a community account configured
                val config =
                    testApplicationConfig().apply {
                        put("community.email", JwtTestSupport.COMMUNITY_EMAIL)
                        put("community.password", JwtTestSupport.COMMUNITY_PASSWORD)
                    }
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
                            versionRequestValidator = VersionRequestValidator(appConfig.semverishCandidates, allowList()),
                        )
                        configureCandidateRouting(candidateService, allowList())
                    }

                    // when
                    val response =
                        client.post("/login") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"email":"${JwtTestSupport.COMMUNITY_EMAIL}","password":"${JwtTestSupport.COMMUNITY_PASSWORD}"}""",
                            )
                        }

                    // then
                    response.status shouldBe HttpStatusCode.OK
                    val token =
                        Json
                            .parseToJsonElement(response.bodyAsText())
                            .jsonObject
                            .getValue("token")
                            .jsonPrimitive.content
                    JWT.decode(token).getClaim("role").asString() shouldBe "community"
                }
            }
        }
    })
