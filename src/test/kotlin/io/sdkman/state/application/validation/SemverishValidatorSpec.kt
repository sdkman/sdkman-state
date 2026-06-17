package io.sdkman.state.application.validation

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class SemverishValidatorSpec :
    ShouldSpec({

        context("validate accepts conforming versions") {

            // The spec's exhaustive "valid" enumeration, plus the case-sensitivity rule
            // (identifiers are case-sensitive, so `-FX` parses exactly like `-fx`).
            val validVersions =
                listOf(
                    "25.0.2",
                    "8.0.472",
                    "0.0.0",
                    "26.0.0-fx",
                    "25.0.2-fx.crac",
                    "27.0.0+ea.16",
                    "25.0.2+1",
                    "22.1.0+1.r17",
                    "25.0.2-fx+1",
                    "25.0.2-FX",
                )

            validVersions.forEach { version ->
                should("accept '$version'") {
                    SemverishValidator.validate(version) shouldBe Unit.right()
                }
            }
        }

        context("validate rejects non-conforming versions") {

            // The spec's exhaustive "invalid" enumeration, including the empty string.
            val invalidVersions =
                listOf(
                    "26",
                    "25.0",
                    "25.0.2.fx",
                    "27.ea.16",
                    "25.0.2.1",
                    "01.0.0",
                    "25.0.2-",
                    "25.0.2+",
                    "25.0.2-fx_crac",
                    "25.0.2++1",
                    "25.0.2--fx",
                    "",
                )

            invalidVersions.forEach { version ->
                should("reject '$version'") {
                    SemverishValidator.validate(version) shouldBe
                        InvalidVersionFormatError(version = version).left()
                }
            }
        }
    })
