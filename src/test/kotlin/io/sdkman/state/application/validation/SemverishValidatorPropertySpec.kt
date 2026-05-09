package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.forAll
import io.sdkman.state.support.shouldBeRight

class SemverishValidatorPropertySpec :
    ShouldSpec({

        // -- Generators --

        val alphanumChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val identChars = alphanumChars + '-'

        val arbNumericComponent: Arb<String> =
            Arb.int(0..9999).map { it.toString() }

        val arbCoreVersion: Arb<String> =
            Arb.bind(
                arbNumericComponent,
                arbNumericComponent,
                arbNumericComponent,
            ) { major, minor, patch -> "$major.$minor.$patch" }

        val arbIdentifier: Arb<String> =
            Arb.bind(
                Arb.of(alphanumChars),
                Arb.list(Arb.of(identChars), 0..8),
            ) { first, rest ->
                val raw = first + rest.joinToString("")
                raw.dropLastWhile { it == '-' }.ifEmpty { first.toString() }
            }

        val arbIdentifierList: Arb<String> =
            Arb.list(arbIdentifier, 1..3).map { it.joinToString(".") }

        val arbOptionalVariant: Arb<String> =
            Arb.choice(
                Arb.constant(""),
                arbIdentifierList.map { "-$it" },
            )

        val arbOptionalBuildMetadata: Arb<String> =
            Arb.choice(
                Arb.constant(""),
                arbIdentifierList.map { "+$it" },
            )

        val arbSemverishVersion: Arb<String> =
            Arb.bind(
                arbCoreVersion,
                arbOptionalVariant,
                arbOptionalBuildMetadata,
            ) { core, variant, metadata -> "$core$variant$metadata" }

        // -- Properties --

        should("accept any version composed from valid semverish parts") {
            forAll(arbSemverishVersion) { version ->
                SemverishValidator.validate(version).shouldBeRight()
                true
            }
        }

        should("produce versions that round-trip through validation unchanged") {
            forAll(arbSemverishVersion) { version ->
                val result = SemverishValidator.validate(version).shouldBeRight()
                result shouldBe version
                true
            }
        }
    })
