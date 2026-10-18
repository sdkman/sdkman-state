package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.support.allowList
import io.sdkman.state.support.configureTestApplication
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
                        configureTestApplication(appConfig, allowList())
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
