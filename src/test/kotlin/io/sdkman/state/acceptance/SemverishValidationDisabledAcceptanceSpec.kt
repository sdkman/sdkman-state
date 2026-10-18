package io.sdkman.state.acceptance

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.allowList
import io.sdkman.state.support.configureTestApplication
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
                        configureTestApplication(appConfig, allowList("java"))
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
