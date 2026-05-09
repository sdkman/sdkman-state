package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class SemverishValidatorSpec :
    ShouldSpec({

        context("valid semverish versions") {

            should("accept three-part version") {
                // given: a standard three-part version
                val version = "25.0.2"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with large patch number") {
                // given: a version with large patch
                val version = "8.0.472"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with all zeros") {
                // given: a version with all zero components
                val version = "0.0.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with variant") {
                // given: a version with a variant section
                val version = "26.0.0-fx"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with dot-separated variant identifiers") {
                // given: a version with combined variants
                val version = "25.0.2-fx.crac"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with build metadata") {
                // given: a version with early-access build metadata
                val version = "27.0.0+ea.16"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with numeric build metadata") {
                // given: a version with rebuild counter
                val version = "25.0.2+1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with combined build metadata identifiers") {
                // given: a version with rebuild + runtime target
                val version = "22.1.0+1.r17"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with both variant and build metadata") {
                // given: a version with variant and build metadata
                val version = "25.0.2-fx+1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }
        }

        context("invalid semverish versions") {

            should("reject bare major version") {
                // given: a version with only major component
                val version = "26"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<InvalidVersionFormatError>()
                    error.field shouldBe "version"
                }
            }

            should("reject version with only major and minor") {
                // given: a version missing the patch component
                val version = "25.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with variant in wrong section using dot") {
                // given: a version with variant after dot instead of dash
                val version = "25.0.2.fx"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with early-access in wrong section") {
                // given: a version with ea fragment in core version
                val version = "27.ea.16"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with rebuild counter in wrong section") {
                // given: a version with fourth dot-separated component
                val version = "25.0.2.1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with leading zero in major") {
                // given: a version with leading zero
                val version = "01.0.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with empty variant section") {
                // given: a version with trailing dash
                val version = "25.0.2-"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with empty build metadata section") {
                // given: a version with trailing plus
                val version = "25.0.2+"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with underscore in identifier") {
                // given: a version with underscore in variant
                val version = "25.0.2-fx_crac"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with duplicate plus sign") {
                // given: a version with double plus
                val version = "25.0.2++1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with duplicate dash sign") {
                // given: a version with double dash introducing empty identifier
                val version = "25.0.2--fx"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject empty string") {
                // given: an empty version string
                val version = ""

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }
        }
    })
