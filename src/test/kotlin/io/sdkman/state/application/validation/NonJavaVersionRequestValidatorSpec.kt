package io.sdkman.state.application.validation

import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.allowList
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class NonJavaVersionRequestValidatorSpec :
    ShouldSpec({

        val validator = VersionRequestValidator(allowList("java", "gradle"))

        context("Non-java version grammar") {

            fun gradleJson(version: String) =
                """
                {
                    "candidate": "gradle",
                    "version": "$version",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/gradle.zip"
                }
                """.trimIndent()

            listOf("1.2.0", "3.2.9.Final", "2.0.0-RC15", "5.23.0.0_2", "1".repeat(50)).forEach { version ->
                should("accept gradle version '$version'") {
                    validator.validateRequest(gradleJson(version)).shouldBeRight().version shouldBe version
                }
            }

            listOf("1.2.0 LTS", "-1.0", "1.0/beta", "1".repeat(51)).forEach { version ->
                should("refuse gradle version '$version' with an InvalidNonJavaVersionError") {
                    val errors = validator.validateRequest(gradleJson(version)).shouldBeLeft()

                    errors.toList() shouldBe listOf(InvalidNonJavaVersionError(version = version))
                    errors.head.message shouldBe "Version '$version' must match ^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$"
                }
            }
        }

        context("Non-java tags") {

            fun gradleJson(tag: String) =
                """{"candidate": "gradle", "version": "8.12", "platform": "UNIVERSAL", "url": "https://example.com/gradle.zip", "tags": ["$tag"]}"""

            listOf("stable", "latest").forEach { tag ->
                should("accept gradle tag '$tag'") {
                    validator.validateRequest(gradleJson(tag)).shouldBeRight().tags shouldBe listOf(tag).some()
                }
            }

            listOf("lts", "27", "Stable").forEach { tag ->
                should("refuse gradle tag '$tag' with an InvalidTagError") {
                    validator.validateRequest(gradleJson(tag)).shouldBeLeft().toList() shouldBe
                        listOf(InvalidTagError("tags[0]", "Tag '$tag' is not allowed; use stable or latest"))
                }
            }
        }
    })
