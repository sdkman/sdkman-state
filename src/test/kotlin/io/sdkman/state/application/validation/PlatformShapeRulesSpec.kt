package io.sdkman.state.application.validation

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version

class PlatformShapeRulesSpec :
    ShouldSpec({

        fun jpx(platform: Platform) = Version("jpx", "1.2.0", platform, "https://example.com/jpx-1.2.0.zip")

        should("refuse a UNIVERSAL row beside a stored LINUX_X64 row") {
            val errors = PlatformShapeRules.validate(jpx(Platform.UNIVERSAL), listOf(Platform.LINUX_X64))

            errors shouldBe listOf(PlatformShapeError(version = "1.2.0"))
            errors.single().field shouldBe "platform"
            errors.single().message shouldBe "Version '1.2.0' cannot mix UNIVERSAL and platform rows"
        }

        should("refuse a LINUX_X64 row beside a stored UNIVERSAL row") {
            PlatformShapeRules.validate(jpx(Platform.LINUX_X64), listOf(Platform.UNIVERSAL)) shouldBe
                listOf(PlatformShapeError(version = "1.2.0"))
        }

        should("accept an overwrite of a stored row beside mixed stored rows") {
            PlatformShapeRules
                .validate(jpx(Platform.LINUX_X64), listOf(Platform.UNIVERSAL, Platform.LINUX_X64))
                .shouldBeEmpty()
        }

        should("accept a MAC_ARM64 row beside stored platform rows") {
            PlatformShapeRules
                .validate(jpx(Platform.MAC_ARM64), listOf(Platform.LINUX_X64, Platform.WINDOWS_X64))
                .shouldBeEmpty()
        }

        should("accept a first row of any platform") {
            PlatformShapeRules.validate(jpx(Platform.UNIVERSAL), emptyList()).shouldBeEmpty()
        }
    })
