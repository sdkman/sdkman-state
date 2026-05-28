package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight

/**
 * Pure-grammar coverage for [SemverishVersionValidator].
 *
 * Cases are organised to mirror the structure of the spec
 * (`specs/semverish-version-validation.md`) so that any future change to the
 * grammar can be traced from a failing test back to a specific spec section:
 *
 *   1. The explicit *Examples — valid and invalid* list (8 valid + 12 invalid).
 *   2. The *Mapping established Java patterns* table — the normalised forms
 *      must be accepted, the originals (where they were the "wrong" shape) must
 *      be rejected.
 *   3. Combined variant + build-metadata cases, called out in the spec but only
 *      partially represented in the explicit list.
 *
 * Exhaustive grammar coverage lives here at the unit level on purpose; the
 * route-level acceptance spec only needs one representative valid/invalid pair.
 */
class SemverishVersionValidatorSpec :
    ShouldSpec({

        context("valid examples from spec §Examples — valid and invalid") {
            // Each row is (description, input). Description doubles as the test
            // name so a single failure points straight at the spec example.
            listOf(
                "plain three-part" to "25.0.2",
                "larger patch" to "8.0.472",
                "all-zero core" to "0.0.0",
                "variant: -fx" to "26.0.0-fx",
                "combined variants: -fx.crac" to "25.0.2-fx.crac",
                "build metadata: +ea.16" to "27.0.0+ea.16",
                "build metadata: +1 (rebuild)" to "25.0.2+1",
                "build metadata: +1.r17 (rebuild + runtime)" to "22.1.0+1.r17",
                "variant and build together: -fx+1" to "25.0.2-fx+1",
            ).forEach { (description, input) ->
                should("accept $description ($input)") {
                    // when: validating a conforming version string
                    val result = SemverishVersionValidator.validate(input)

                    // then: validator returns the input unchanged
                    result.shouldBeRight() shouldBe input
                }
            }
        }

        context("invalid examples from spec §Examples — valid and invalid") {
            // The "why" column matches the parenthetical in the spec so that a
            // failing test reads like a contract violation rather than a bare
            // string mismatch.
            listOf(
                "missing minor and patch" to "26",
                "missing patch" to "25.0",
                "variant in the wrong section (must use `-`)" to "25.0.2.fx",
                "early-access fragment in the wrong section (must use `+ea.16`)" to "27.ea.16",
                "rebuild counter in the wrong section (must use `+1`)" to "25.0.2.1",
                "leading zero in major" to "01.0.0",
                "empty variant section" to "25.0.2-",
                "empty build metadata section" to "25.0.2+",
                "`_` is not an allowed identifier character" to "25.0.2-fx_crac",
                "duplicate `+`" to "25.0.2++1",
                "duplicate `-`" to "25.0.2--fx",
                "empty string" to "",
            ).forEach { (reason, input) ->
                should("reject $reason ('$input')") {
                    // when: validating a non-conforming version string
                    val result = SemverishVersionValidator.validate(input)

                    // then: validator surfaces a validation error on the
                    // `version` field. The specific subtype is asserted in a
                    // dedicated test below to keep these grammar cases focused
                    // on coverage rather than type contract.
                    val error = result.shouldBeLeft()
                    error.field shouldBe "version"
                }
            }
        }

        context("error type contract") {
            // Lock the public error type so that downstream wiring
            // (VersionRequestValidator, ValidationFailure mapping) can
            // pattern-match on it without depending on the placeholder
            // `InvalidOptionalFieldError` it previously borrowed.
            should("return InvalidSemverishVersionError carrying the offending input") {
                // when: validating a non-conforming version
                val result = SemverishVersionValidator.validate("26")

                // then: the error is the dedicated semverish subtype, the
                // offending input is echoed back, and the message indicates
                // a semverish format violation.
                val error = result.shouldBeLeft()
                val semverishError = error.shouldBeInstanceOf<InvalidSemverishVersionError>()
                semverishError.field shouldBe "version"
                semverishError.version shouldBe "26"
                semverishError.message shouldBe "Version '26' does not conform to the semverish format"
            }
        }

        context("mapping table — normalised forms must be accepted") {
            // From the spec's "Mapping established Java patterns to semverish"
            // table. Producers normalise *to* these shapes before calling the
            // API, so the parser is what enforces the contract on writes.
            listOf(
                "already three-part" to "25.0.2",
                "bare major normalised" to "26.0.0",
                "FX variant normalised" to "25.0.2-fx",
                "CRaC variant normalised" to "21.0.10-crac",
                "combined variants normalised" to "25.0.2-fx.crac",
                "early-access build normalised" to "27.0.0+ea.16",
                "rebuild counter normalised" to "25.0.2+1",
                "runtime target normalised" to "25.0.2+r25",
                "rebuild + runtime target normalised" to "22.1.0+1.r17",
            ).forEach { (description, input) ->
                should("accept $description ($input)") {
                    SemverishVersionValidator.validate(input).shouldBeRight() shouldBe input
                }
            }
        }

        context("mapping table — original (pre-normalisation) forms must be rejected") {
            // These are the *Original* column entries that used the wrong
            // separator (`.` instead of `-` / `+`). The parser must reject them
            // so that producers cannot silently store un-normalised data.
            listOf(
                "bare major (un-padded)" to "26",
                "FX variant with `.` separator" to "25.0.2.fx",
                "CRaC variant with `.` separator" to "21.0.10.crac",
                "early-access fragment with `.` separator" to "27.ea.16",
                "rebuild counter with `.` separator" to "25.0.2.1",
                "runtime target with `.` separator" to "25.0.2.r25",
                "rebuild + runtime target with `.` separator" to "22.1.0.1.r17",
            ).forEach { (reason, input) ->
                should("reject $reason ('$input')") {
                    val error = SemverishVersionValidator.validate(input).shouldBeLeft()
                    error.field shouldBe "version"
                }
            }
        }

        context("combined variant + build-metadata cases") {
            // The spec only spells out `25.0.2-fx+1` explicitly, but the grammar
            // is meant to compose. Lock that composition down here so a future
            // refactor cannot accidentally narrow it.
            should("accept single variant + single build identifier (25.0.2-fx+1)") {
                SemverishVersionValidator.validate("25.0.2-fx+1").shouldBeRight() shouldBe "25.0.2-fx+1"
            }

            should("accept multi-part variant + multi-part build (25.0.2-fx.crac+ea.16)") {
                SemverishVersionValidator
                    .validate("25.0.2-fx.crac+ea.16")
                    .shouldBeRight() shouldBe "25.0.2-fx.crac+ea.16"
            }

            should("accept variant + rebuild + runtime (22.1.0-fx+1.r17)") {
                SemverishVersionValidator
                    .validate("22.1.0-fx+1.r17")
                    .shouldBeRight() shouldBe "22.1.0-fx+1.r17"
            }

            should("reject empty variant section followed by build metadata (25.0.2-+1)") {
                // Section markers must be followed by at least one identifier;
                // a bare `-` between the patch and `+` is the same shape error
                // as the spec's `25.0.2-` example, just with build appended.
                SemverishVersionValidator.validate("25.0.2-+1").shouldBeLeft()
            }
        }
    })
