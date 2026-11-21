package io.sdkman.state.adapter.primary.rest

import arrow.core.left
import arrow.core.none
import arrow.core.right
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.sdkman.state.application.validation.JavaPoster
import io.sdkman.state.application.validation.JavaVersionRequestValidator
import io.sdkman.state.application.validation.VersionRequestValidator
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.VersionPlatformRepository
import io.sdkman.state.support.allowList
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.unreadyAllowList

class WriteValidationUnitSpec :
    ShouldSpec({
        fun versionJson(
            candidate: String = "jpx",
            version: String = "1.2.0",
            platform: String = "LINUX_X64",
        ) = """
            {
                "candidate": "$candidate",
                "version": "$version",
                "platform": "$platform",
                "url": "https://example.com/$candidate-$version.zip"
            }
            """.trimIndent()

        fun javaVersionJson(version: String = "25.0.5.0+3") =
            """
            {
                "version": "$version",
                "platform": "LINUX_X64",
                "url": "https://example.com/java-$version.tar.gz"
            }
            """.trimIndent()

        fun candidateJson(websiteUrl: String = "https://jpx.example.com/") =
            """
            {
                "candidate": "jpx",
                "name": "JPX",
                "description": "A JPX candidate for tests.",
                "website_url": "$websiteUrl"
            }
            """.trimIndent()

        val versionPlatforms = mockk<VersionPlatformRepository>()
        coEvery { versionPlatforms.findPlatforms(any(), any(), any()) } returns emptyList<Platform>().right()
        val validator = VersionRequestValidator(allowList("jpx"), versionPlatforms)
        val unreadyValidator = VersionRequestValidator(unreadyAllowList(), versionPlatforms)
        val javaValidator = JavaVersionRequestValidator(allowList(JAVA_CANDIDATE))
        val unreadyJavaValidator = JavaVersionRequestValidator(unreadyAllowList())

        context("checkVersionBody") {
            should("return the parsed version for a valid body of a registered candidate") {
                // given: a valid body for the registered candidate jpx
                val body = versionJson()

                // when: checking the body
                val result = validator.checkVersionBody(body)

                // then: the version carries the posted fields
                val version = result.shouldBeRight()
                version.candidate shouldBe "jpx"
                version.version shouldBe "1.2.0"
                version.platform shouldBe Platform.LINUX_X64
            }

            should("reject a body for an unregistered candidate as invalid on field candidate") {
                // given: a body for gradle, which the registry does not hold
                val body = versionJson(candidate = "gradle")

                // when: checking the body
                val result = validator.checkVersionBody(body)

                // then: the rejection names the candidate field only
                val rejection = result.shouldBeLeft().shouldBeInstanceOf<RejectedBody.Invalid>()
                rejection.failures.map { it.field } shouldContainExactly listOf("candidate")
            }

            should("answer registry unavailable for a valid body when the registry has not loaded") {
                // given: a validator over a registry that never loaded
                val body = versionJson()

                // when: checking a valid body
                val result = unreadyValidator.checkVersionBody(body)

                // then: the registry outage wins
                result shouldBeLeft RejectedBody.RegistryUnavailable
            }

            should("answer registry unavailable before parsing a malformed body") {
                // given: a validator over a registry that never loaded
                val body = "not json"

                // when: checking an unparseable body
                val result = unreadyValidator.checkVersionBody(body)

                // then: readiness is checked before the body is parsed
                result shouldBeLeft RejectedBody.RegistryUnavailable
            }

            should("reject a UNIVERSAL body beside a stored LINUX_X64 row as invalid on field platform") {
                // given: jpx 2.0.0 is stored on LINUX_X64 only
                coEvery { versionPlatforms.findPlatforms("jpx", "2.0.0", none()) } returns
                    listOf(Platform.LINUX_X64).right()
                val body = versionJson(version = "2.0.0", platform = "UNIVERSAL")

                // when: checking a UNIVERSAL body for the same version
                val result = validator.checkVersionBody(body)

                // then: the rejection names the platform field only
                val rejection = result.shouldBeLeft().shouldBeInstanceOf<RejectedBody.Invalid>()
                rejection.failures.map { it.field } shouldContainExactly listOf("platform")
            }

            should("answer database unavailable when the stored platforms cannot be read") {
                // given: the platform read of jpx 3.0.0 fails
                val failure = DatabaseFailure.QueryExecutionFailure("Failed to find version platforms", RuntimeException())
                coEvery { versionPlatforms.findPlatforms("jpx", "3.0.0", none()) } returns failure.left()
                val body = versionJson(version = "3.0.0")

                // when: checking a valid body
                val result = validator.checkVersionBody(body)

                // then: the read failure is passed on
                result shouldBeLeft RejectedBody.DatabaseUnavailable(failure)
            }
        }

        context("checkJavaVersionBody") {
            should("return the java version for a valid vendor body") {
                // given: a vendor body with a semverish version
                val body = javaVersionJson()

                // when: checking the body for a vendor
                val result = javaValidator.checkJavaVersionBody(body, JavaPoster.VENDOR)

                // then: the version is fixed to java and carries the posted version
                val version = result.shouldBeRight()
                version.candidate shouldBe JAVA_CANDIDATE
                version.version shouldBe "25.0.5.0+3"
            }

            should("reject a legacy version from a vendor as invalid on field version") {
                // given: a body with a version outside the grammar
                val body = javaVersionJson(version = "25.0.3")

                // when: checking the body for a vendor
                val result = javaValidator.checkJavaVersionBody(body, JavaPoster.VENDOR)

                // then: the rejection names the version field only
                val rejection = result.shouldBeLeft().shouldBeInstanceOf<RejectedBody.Invalid>()
                rejection.failures.map { it.field } shouldContainExactly listOf("version")
            }

            should("accept a legacy version from the admin") {
                // given: a body with a version outside the grammar
                val body = javaVersionJson(version = "25.0.3")

                // when: checking the body for the admin
                val result = javaValidator.checkJavaVersionBody(body, JavaPoster.ADMIN)

                // then: the legacy version is kept as given
                result.shouldBeRight().version shouldBe "25.0.3"
            }

            should("answer registry unavailable before parsing a malformed body") {
                // given: a java validator over a registry that never loaded
                val body = "not json"

                // when: checking an unparseable body
                val result = unreadyJavaValidator.checkJavaVersionBody(body, JavaPoster.VENDOR)

                // then: readiness is checked before the body is parsed
                result shouldBeLeft RejectedBody.RegistryUnavailable
            }
        }

        context("checkCandidateBody") {
            should("return the registration for a valid candidate body") {
                // given: a valid candidate registration body
                val body = candidateJson()

                // when: checking the body
                val result = checkCandidateBody(body)

                // then: the registration carries the identifier
                result.shouldBeRight().candidate shouldBe "jpx"
            }

            should("reject a non-https website_url as invalid on field website_url") {
                // given: a body whose website_url is plain http
                val body = candidateJson(websiteUrl = "http://jpx.example.com/")

                // when: checking the body
                val result = checkCandidateBody(body)

                // then: the rejection names the website_url field only
                val rejection = result.shouldBeLeft().shouldBeInstanceOf<RejectedBody.Invalid>()
                rejection.failures.map { it.field } shouldContainExactly listOf("website_url")
            }
        }
    })
