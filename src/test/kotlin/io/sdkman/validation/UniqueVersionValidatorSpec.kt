package io.sdkman.validation

import arrow.core.None
import arrow.core.left
import arrow.core.right
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion

class UniqueVersionValidatorSpec :
    ShouldSpec({

        context("validate") {

            should("accept unique version with valid fields") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
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
                        distribution = None,
                        platform = Platform.UNIVERSAL,
                    )

                UniqueVersionValidator.validate(uniqueVersion) shouldBe uniqueVersion.right()
            }

            should("accept version with suffix like -RC1") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "kotlin",
                        version = "1.9.0-RC1",
                        distribution = None,
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

            should("reject when version is blank") {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )

                val expected = EmptyFieldError("version").left()
                UniqueVersionValidator.validate(uniqueVersion) shouldBe expected
            }
        }
    })
