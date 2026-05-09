package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class VersionRequestSemverishValidatorSpec :
    ShouldSpec({

        val validator = VersionRequestValidator(semverishCandidates = setOf("java"))

        should("reject non-semverish version for opted-in candidate java") {
            // given: a non-semverish version for java
            val json =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2.fx",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java.tar.gz"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: exactly one InvalidVersionFormatError
            val errors = result.shouldBeLeft()
            errors.size shouldBe 1
            errors.head.shouldBeInstanceOf<InvalidVersionFormatError>()
            errors.head.field shouldBe "version"
        }

        should("not produce semverish error when candidate validation already failed") {
            // given: an invalid candidate with a non-semverish version
            val json =
                """
                {
                    "candidate": "invalid-candidate",
                    "version": "25.0.2.fx",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/file.tar.gz"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: only InvalidCandidateError, no semverish error
            val errors = result.shouldBeLeft()
            errors.any { it is InvalidCandidateError } shouldBe true
            errors.none { it is InvalidVersionFormatError } shouldBe true
        }

        should("not produce semverish error when version is empty") {
            // given: java candidate with empty version
            val json =
                """
                {
                    "candidate": "java",
                    "version": "",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/file.tar.gz"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: only EmptyFieldError for version, no semverish error
            val errors = result.shouldBeLeft()
            errors.any { it is EmptyFieldError && it.field == "version" } shouldBe true
            errors.none { it is InvalidVersionFormatError } shouldBe true
        }

        should("not produce semverish error for non-opted-in candidate") {
            // given: scala (not opted in) with a non-semverish version
            val json =
                """
                {
                    "candidate": "scala",
                    "version": "3.0.0-beta-1",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/scala.tar.gz"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: validation succeeds, no semverish error
            result.shouldBeRight()
        }

        should("accept valid semverish version for opted-in candidate java") {
            // given: a valid semverish version for java
            val json =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2-fx",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java.tar.gz"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: validation succeeds
            result.shouldBeRight()
        }

        should("accumulate semverish error alongside other validation errors") {
            // given: java with non-semverish version and invalid URL
            val json =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2.fx",
                    "platform": "LINUX_X64",
                    "url": "http://not-https.com/file.zip"
                }
                """.trimIndent()

            // when: validating the request
            val result = validator.validateRequest(json)

            // then: both errors are accumulated
            val errors = result.shouldBeLeft()
            errors.any { it is InvalidUrlError } shouldBe true
            errors.any { it is InvalidVersionFormatError } shouldBe true
        }
    })
