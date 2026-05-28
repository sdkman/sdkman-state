package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

/**
 * Wiring-level coverage for the per-candidate semverish opt-in on
 * [VersionRequestValidator]. The validator's `strictSemverishCandidates`
 * parameter is the integration point for the feature described in
 * `specs/semverish-version-validation.md`. The cases below lock the four
 * axes of the contract:
 *
 *  1. opted-in × conforming → success
 *  2. opted-in × non-conforming → [InvalidSemverishVersionError]
 *  3. not-opted-in × non-conforming → success (current behaviour preserved)
 *  4. accumulation alongside other field errors
 *
 * Plus two short-circuit cases that lock down the spec's stated validation
 * order ("semverish only runs when candidate validation succeeds AND
 * candidate is in the opt-in set AND version is non-blank") so that a
 * single root cause never surfaces as two errors.
 *
 * Exhaustive grammar coverage stays in [SemverishVersionValidatorSpec].
 * This spec is intentionally separate from [VersionRequestValidatorSpec]
 * so that file stays below detekt's `LargeClass` threshold without
 * weakening the rule.
 */
class VersionRequestValidatorSemverishOptInSpec :
    ShouldSpec({

        should("accept conforming version when candidate is in opt-in set") {
            // given: an opted-in candidate with a semverish-conforming version
            val json =
                """
                {
                    "candidate": "java",
                    "version": "25.0.2",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-25.0.2.tar.gz"
                }
                """.trimIndent()

            // when: validating with the opt-in set including the candidate
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("java"),
                )

            // then: validation succeeds and carries the version through unchanged
            result.shouldBeRight()
            result.onRight { version ->
                version.candidate shouldBe "java"
                version.version shouldBe "25.0.2"
            }
        }

        should("reject non-conforming version with InvalidSemverishVersionError when candidate is in opt-in set") {
            // given: an opted-in candidate with a version that fails the grammar
            // (bare major, no minor/patch — first invalid example in the spec)
            val json =
                """
                {
                    "candidate": "java",
                    "version": "26",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java-26.tar.gz"
                }
                """.trimIndent()

            // when: validating with the opt-in set including the candidate
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("java"),
                )

            // then: validation fails with the dedicated semverish subtype
            result.shouldBeLeft()
            result.onLeft { errors ->
                errors.size shouldBe 1
                val error = errors.head.shouldBeInstanceOf<InvalidSemverishVersionError>()
                error.field shouldBe "version"
                error.version shouldBe "26"
                error.message shouldContain "does not conform to the semverish format"
            }
        }

        should("accept non-conforming version when candidate is NOT in opt-in set") {
            // given: a candidate outside the opt-in set with a version that would
            // fail semverish — current (pre-opt-in) behaviour must be preserved
            val json =
                """
                {
                    "candidate": "scala",
                    "version": "26",
                    "platform": "UNIVERSAL",
                    "url": "https://example.com/scala.tar.gz"
                }
                """.trimIndent()

            // when: validating with an opt-in set that does NOT include scala
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("java"),
                )

            // then: validation succeeds — semverish enforcement is per-candidate
            result.shouldBeRight()
            result.onRight { version ->
                version.candidate shouldBe "scala"
                version.version shouldBe "26"
            }
        }

        should("accumulate InvalidSemverishVersionError alongside other field errors") {
            // given: opted-in candidate, non-conforming version, AND other invalid
            // fields. The semverish error must surface together with the others
            // so the client sees all problems in a single response.
            val json =
                """
                {
                    "candidate": "java",
                    "version": "26",
                    "platform": "INVALID_PLATFORM",
                    "url": "http://not-https.example.com/java.tar.gz"
                }
                """.trimIndent()

            // when: validating with the opt-in set including the candidate
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("java"),
                )

            // then: all three errors are present in the same NEL
            result.shouldBeLeft()
            result.onLeft { errors ->
                errors.size shouldBe 3
                errors.any { it is InvalidSemverishVersionError && it.version == "26" } shouldBe true
                errors.any { it is InvalidPlatformError } shouldBe true
                errors.any { it is InvalidUrlError } shouldBe true
            }
        }

        should("not run semverish check when candidate validation fails (no double-counting)") {
            // given: an opted-in *name* that is not in the allowed-candidates list,
            // plus a version that would fail semverish. The candidate failure must
            // short-circuit the semverish check so the user sees one error per
            // root cause, not two errors blaming the same input.
            val json =
                """
                {
                    "candidate": "not-a-known-candidate",
                    "version": "26",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/file.tar.gz"
                }
                """.trimIndent()

            // when: validating with the bogus candidate name in the opt-in set
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("not-a-known-candidate"),
                )

            // then: only the candidate error fires — semverish stays silent
            result.shouldBeLeft()
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<InvalidCandidateError>()
            }
        }

        should("not run semverish check when version field is blank (no double-counting)") {
            // given: opted-in candidate but a blank version. The empty-field error
            // already covers the failure mode; running the grammar check on the
            // blank string would add an `InvalidSemverishVersionError("")` that
            // tells the user nothing new.
            val json =
                """
                {
                    "candidate": "java",
                    "version": "",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java.tar.gz"
                }
                """.trimIndent()

            // when: validating with java in the opt-in set
            val result =
                VersionRequestValidator.validateRequest(
                    json,
                    strictSemverishCandidates = setOf("java"),
                )

            // then: only the empty-version error fires
            result.shouldBeLeft()
            result.onLeft { errors ->
                errors.size shouldBe 1
                errors.head.shouldBeInstanceOf<EmptyFieldError>()
                errors.head.field shouldBe "version"
            }
        }

        should("default to empty opt-in set so legacy call sites keep current behaviour") {
            // given: a candidate that would be opted in *if* a set were provided,
            // with a non-conforming version
            val json =
                """
                {
                    "candidate": "java",
                    "version": "26",
                    "platform": "LINUX_X64",
                    "url": "https://example.com/java.tar.gz"
                }
                """.trimIndent()

            // when: validating WITHOUT supplying a strict set (the default)
            val result = VersionRequestValidator.validateRequest(json)

            // then: validation succeeds — the default emptySet() means nothing
            // is enforced, preserving the contract before this feature landed
            result.shouldBeRight()
            result.onRight { version ->
                version.version shouldBe "26"
            }
        }
    })
