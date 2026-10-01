package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

class SemverishValidatorSpec :
    ShouldSpec({

        context("valid semverish versions") {
            should("accept four-part version") {
                // given: a three-part release padded with a zero fourth component
                val version = "25.0.2.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept four-part version with patch component") {
                // given: a JEP 322 version carrying its patch counter in the fourth component
                val version = "25.0.2.1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with large update number") {
                // given: a version with a large third component
                val version = "8.0.472.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with all zeros") {
                // given: a version with all four components zero
                val version = "0.0.0.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with variant") {
                // given: a four-part version with a variant section
                val version = "26.0.0.0-fx"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with early-access build metadata") {
                // given: a padded early-access version
                val version = "27.0.0.0+ea.16"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept padded early-access version with build number") {
                // given: an early-access version padded to four components
                val version = "29.0.0.0+ea.10"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept patch component with runtime target in build metadata") {
                // given: a version with patch component and runtime target
                val version = "22.1.0.1+r17"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept five-part vendor build with fifth element in build metadata") {
                // given: a vendor build whose fifth numeric element moved to build metadata
                val version = "21.0.5.11+1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }

            should("accept version with both variant and build metadata") {
                // given: a four-part version with variant and build metadata
                val version = "25.0.2.0-fx+1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation succeeds
                result shouldBeRight version
            }
        }

        context("invalid semverish versions") {
            should("reject three-component core") {
                // given: a version with only three core components
                val version = "25.0.2"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails because four core components are mandatory
                result.shouldBeLeft()
            }

            should("reject three-component core with build metadata") {
                // given: a three-component version with build metadata
                val version = "29.0.0+ea.10"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails because four core components are mandatory
                result.shouldBeLeft()
            }

            should("reject three-component core with variant") {
                // given: a three-component version with a variant
                val version = "26.0.0-fx"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails because four core components are mandatory
                result.shouldBeLeft()
            }

            should("reject bare major version") {
                // given: a version with only major component
                val version = "26"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails with a version format error
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<InvalidVersionFormatError>()
                    error.field shouldBe "version"
                }
            }

            should("reject version with only major and minor") {
                // given: a version with two core components
                val version = "25.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with five numeric core components") {
                // given: a version whose fifth numeric element belongs in build metadata
                val version = "25.0.2.1.1"

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

                // then: validation fails because the fourth component is not numeric
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

            should("reject version with leading zero in major") {
                // given: a version with leading zero in major
                val version = "01.0.0.0"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with leading zero in fourth component") {
                // given: a version with a zero-padded fourth component
                val version = "25.0.2.00"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with empty variant section") {
                // given: a version with trailing dash
                val version = "25.0.2.0-"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with empty build metadata section") {
                // given: a version with trailing plus
                val version = "25.0.2.0+"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with underscore in identifier") {
                // given: a version with underscore in variant
                val version = "25.0.2.0-fx_crac"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with combined variants") {
                // given: a version combining both variants in one section
                val version = "25.0.2.0-fx.crac"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails because the variant is exactly one of fx or crac
                result.shouldBeLeft()
            }

            should("reject version with variant outside the fx or crac vocabulary") {
                // given: a version with an unknown variant
                val version = "25.0.4.0-graal"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with upper-case variant") {
                // given: a version with a variant in the wrong case
                val version = "25.0.2.0-FX"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails because the vocabulary is case-sensitive
                result.shouldBeLeft()
            }

            should("reject version with duplicate plus sign") {
                // given: a version with double plus
                val version = "25.0.2.0++1"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }

            should("reject version with duplicate dash sign") {
                // given: a version with double dash introducing empty identifier
                val version = "25.0.2.0--fx"

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

            should("reject version with trailing hyphen in build metadata identifier") {
                // given: a version with a trailing hyphen in build metadata
                val version = "25.0.2.0+ea-"

                // when: validating the version
                val result = SemverishValidator.validate(version)

                // then: validation fails
                result.shouldBeLeft()
            }
        }
    })
