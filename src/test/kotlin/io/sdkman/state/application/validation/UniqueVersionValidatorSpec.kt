package io.sdkman.state.application.validation

import arrow.core.left
import arrow.core.none
import arrow.core.right
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.support.shouldBeLeft

class UniqueVersionValidatorSpec :
    ShouldSpec({

        context("validate") {

            should("accept unique version with valid fields") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "gradle",
                        version = "17.0.1",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                UniqueVersionValidator.validate(uniqueVersion) shouldBe uniqueVersion.right()
            }

            should("accept unique version with no distribution") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "maven",
                        version = "3.9.0",
                        distribution = none(),
                        platform = Platform.UNIVERSAL,
                    )

                UniqueVersionValidator.validate(uniqueVersion) shouldBe uniqueVersion.right()
            }

            should("accept version with suffix like -RC1") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "kotlin",
                        version = "1.9.0-RC1",
                        distribution = none(),
                        platform = Platform.UNIVERSAL,
                    )

                UniqueVersionValidator.validate(uniqueVersion) shouldBe uniqueVersion.right()
            }

            should("reject when candidate is blank") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "",
                        version = "17.0.1",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                val expected = EmptyFieldError("candidate").left()
                UniqueVersionValidator.validate(uniqueVersion) shouldBe expected
            }

            should("reject java with a JavaRouteError on candidate naming DELETE /versions/java") {
                // given: a java row's unique version
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "25.0.4.1+1",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                // when: validating it for the generic delete
                val error = UniqueVersionValidator.validate(uniqueVersion).shouldBeLeft()

                // then: the error identifies candidate and points at the java route
                (error.field to error.message) shouldBe
                    ("candidate" to "candidate 'java' is not accepted here; use DELETE /versions/java")
            }

            should("reject when version is blank") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "gradle",
                        version = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                val expected = EmptyFieldError("version").left()
                UniqueVersionValidator.validate(uniqueVersion) shouldBe expected
            }
        }
    })
