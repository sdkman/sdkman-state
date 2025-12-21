package io.sdkman.validation

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform

class VersionRequestValidatorSpec : ShouldSpec({

    context("Happy path tests") {

        should("validate a request with all required fields and no optional fields") {
            // given: valid JSON with only required fields
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds
            result.isRight() shouldBe true
            result.onRight { version ->
                version.candidate shouldBe "java"
                version.version shouldBe "17.0.1"
                version.platform shouldBe Platform.LINUX_X64
                version.url shouldBe "https://example.com/java-17.0.1.tar.gz"
                version.visible shouldBe None
                version.distribution shouldBe None
                version.md5sum shouldBe None
                version.sha256sum shouldBe None
                version.sha512sum shouldBe None
            }
        }

        should("validate a request with all required fields and valid optional hash fields") {
            // given: valid JSON with hash fields
            val json = """
                {
                    "candidate": "gradle",
                    "version": "8.0.0",
                    "platform": "UNIVERSAL",
                    "url": "https://services.gradle.org/distributions/gradle-8.0.0-bin.zip",
                    "md5sum": "abc123def456abc123def456abc123de",
                    "sha256sum": "abc123def456abc123def456abc123def456abc123def456abc123def456abc1",
                    "sha512sum": "abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123de"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds with hash fields as Some
            result.isRight() shouldBe true
            result.onRight { version ->
                version.candidate shouldBe "gradle"
                version.md5sum shouldBe "abc123def456abc123def456abc123de".some()
                version.sha256sum shouldBe "abc123def456abc123def456abc123def456abc123def456abc123def456abc1".some()
                version.sha512sum shouldBe "abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123de".some()
            }
        }

        should("validate a request with distribution field") {
            // given: valid JSON with distribution
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "distribution": "TEMURIN"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds with distribution as Some
            result.isRight() shouldBe true
            result.onRight { version ->
                version.distribution shouldBe Distribution.TEMURIN.some()
            }
        }

        should("validate a request with visible field") {
            // given: valid JSON with visible field
            val json = """
                {
                    "candidate": "maven",
                    "version": "3.9.0",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/maven-3.9.0.zip",
                    "visible": true
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds with visible as Some
            result.isRight() shouldBe true
            result.onRight { version ->
                version.visible shouldBe true.some()
            }
        }

        should("accept version strings with suffixes like -RC1") {
            // given: version with suffix
            val json = """
                {
                    "candidate": "kotlin",
                    "version": "1.9.0-RC1",
                    "platform": "UNIVERSAL",
                    "url": "https://github.com/JetBrains/kotlin/releases/download/1.9.0-RC1/kotlin.zip"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds
            result.isRight() shouldBe true
            result.onRight { version ->
                version.version shouldBe "1.9.0-RC1"
            }
        }

        should("accept version strings with suffixes like -beta-1") {
            // given: version with beta suffix
            val json = """
                {
                    "candidate": "scala",
                    "version": "3.0.0-beta-1",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/scala-3.0.0-beta-1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds
            result.isRight() shouldBe true
            result.onRight { version ->
                version.version shouldBe "3.0.0-beta-1"
            }
        }

        should("accept hash values with mixed case hexadecimal characters") {
            // given: hash with mixed case
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "md5sum": "AbC123DeF456AbC123DeF456AbC123dE"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds
            result.isRight() shouldBe true
            result.onRight { version ->
                version.md5sum shouldBe "AbC123DeF456AbC123DeF456AbC123dE".some()
            }
        }

        should("accept hash values with all uppercase hexadecimal characters") {
            // given: hash with uppercase
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "sha256sum": "ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC1"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds
            result.isRight() shouldBe true
            result.onRight { version ->
                version.sha256sum shouldBe "ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC1".some()
            }
        }
    }

    context("Unhappy path tests - required fields") {

        should("fail when candidate field is missing") {
            // given: JSON without candidate field
            val json = """
                {
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "candidate"
                errors.head.message shouldBe "candidate cannot be empty"
            }
        }

        should("fail when version field is missing") {
            // given: JSON without version field
            val json = """
                {
                    "candidate": "java",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "version"
            }
        }

        should("fail when platform field is missing") {
            // given: JSON without platform field
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "platform"
            }
        }

        should("fail when url field is missing") {
            // given: JSON without url field
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "url"
            }
        }

        should("fail when candidate field is empty") {
            // given: JSON with empty candidate field
            val json = """
                {
                    "candidate": "",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "candidate"
            }
        }

        should("fail when version field is empty") {
            // given: JSON with empty version field
            val json = """
                {
                    "candidate": "java",
                    "version": "",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "version"
            }
        }

        should("fail when url field is empty") {
            // given: JSON with empty url field
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": ""
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "url"
            }
        }
    }

    context("Unhappy path tests - field validation") {

        should("fail when candidate is not in allowed list") {
            // given: JSON with invalid candidate
            val json = """
                {
                    "candidate": "invalid-candidate",
                    "version": "1.0.0",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/file.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidCandidateError>()
                errors.head.message shouldBe "Candidate 'invalid-candidate' is not valid. Allowed values: java, maven, gradle, kotlin, scala, groovy, sbt"
            }
        }

        should("fail when URL is not HTTPS") {
            // given: JSON with HTTP URL
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "http://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidUrlError>()
                errors.head.message shouldBe "URL 'http://example.com/java-17.0.1.tar.gz' must be a valid HTTPS URL"
            }
        }

        should("fail when URL is malformed") {
            // given: JSON with malformed URL
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "not-a-url"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidUrlError>()
            }
        }

        should("fail when platform enum value is invalid") {
            // given: JSON with invalid platform
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "INVALID_PLATFORM",
                    "url": "https://example.com/java-17.0.1.tar.gz"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidPlatformError>()
                errors.head.message shouldBe "Platform 'INVALID_PLATFORM' is not valid"
            }
        }

        should("fail when distribution enum value is invalid") {
            // given: JSON with invalid distribution
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "distribution": "INVALID_DISTRO"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidDistributionError>()
                errors.head.message shouldBe "Distribution 'INVALID_DISTRO' is not valid"
            }
        }

        should("fail when MD5 hash has wrong length") {
            // given: JSON with short MD5 hash
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "md5sum": "tooshort"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidHashFormatError>()
                errors.head.message shouldBe "md5sum must be a valid hexadecimal hash of 32 characters, got: 'tooshort'"
            }
        }

        should("fail when MD5 hash has non-hex characters") {
            // given: JSON with non-hex MD5
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "md5sum": "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz1"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidHashFormatError>()
            }
        }

        should("fail when SHA256 hash has wrong length") {
            // given: JSON with wrong length SHA256
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "sha256sum": "abc123"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidHashFormatError>()
                errors.head.message shouldBe "sha256sum must be a valid hexadecimal hash of 64 characters, got: 'abc123'"
            }
        }

        should("fail when SHA512 hash has wrong length") {
            // given: JSON with wrong length SHA512
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "sha512sum": "abc123"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidHashFormatError>()
                errors.head.message shouldBe "sha512sum must be a valid hexadecimal hash of 128 characters, got: 'abc123'"
            }
        }

        should("fail when hash field is present but empty") {
            // given: JSON with empty hash
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-17.0.1.tar.gz",
                    "md5sum": ""
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation fails
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.head.shouldBeInstanceOf<InvalidOptionalFieldError>()
                errors.head.message shouldBe "md5sum is invalid: field cannot be empty"
            }
        }
    }

    context("Unhappy path tests - multiple errors") {

        should("accumulate multiple required field errors") {
            // given: JSON with multiple missing fields
            val json = """
                {
                    "visible": true
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: all errors are accumulated
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 4
                errors.map { it.field }.toSet() shouldBe setOf("candidate", "version", "platform", "url")
            }
        }

        should("accumulate multiple field validation errors") {
            // given: JSON with multiple invalid fields
            val json = """
                {
                    "candidate": "invalid-candidate",
                    "version": "",
                    "platform": "INVALID_PLATFORM",
                    "url": "http://not-https.com/file.zip",
                    "sha256sum": ""
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: all errors are accumulated
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 5
                errors.any { it is InvalidCandidateError } shouldBe true
                errors.any { it is EmptyFieldError && it.field == "version" } shouldBe true
                errors.any { it is InvalidPlatformError } shouldBe true
                errors.any { it is InvalidUrlError } shouldBe true
                errors.any { it is InvalidOptionalFieldError && it.field == "sha256sum" } shouldBe true
            }
        }

        should("accumulate errors for all invalid hash fields") {
            // given: JSON with all invalid hashes
            val json = """
                {
                    "candidate": "java",
                    "version": "17.0.1",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java.tar.gz",
                    "md5sum": "tooshort",
                    "sha256sum": "not-a-hex-value-!!!",
                    "sha512sum": "ABC123"
                }
            """.trimIndent()

            // when: validating the request
            val result = VersionRequestValidator.validateRequest(json)

            // then: all hash errors are accumulated
            result.isLeft() shouldBe true
            result.onLeft { errors ->
                errors.size shouldBe 3
                errors.all { it is InvalidHashFormatError } shouldBe true
                errors.map { it.field }.toSet() shouldBe setOf("md5sum", "sha256sum", "sha512sum")
            }
        }
    }
})
