package io.sdkman.validation

import arrow.core.None
import arrow.core.Some
import arrow.core.left
import arrow.core.right
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version

class VersionValidatorSpec : ShouldSpec({

    context("validateVersion") {
        
        should("accept version with no distribution") {
            val version = Version(
                candidate = "maven",
                version = "3.9.0",
                platform = Platform.UNIVERSAL,
                url = "https://example.com/maven-3.9.0.zip",
                visible = true.some(),
                distribution = None
            )

            VersionValidator.validateVersion(version) shouldBe version.right()
        }
        
        should("accept version with distribution when version does not contain distribution suffix") {
            val version = Version(
                candidate = "java",
                version = "17.0.1",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some()
            )

            VersionValidator.validateVersion(version) shouldBe version.right()
        }
        
        should("reject version with distribution when version contains distribution suffix") {
            val version = Version(
                candidate = "java",
                version = "17.0.1-tem",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some()
            )

            val expected = DistributionSuffixError("17.0.1-tem", "tem").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }

        should("reject version with tem suffix when using TEMURIN distribution") {
            val version = Version(
                candidate = "java",
                version = "17.0.1-tem",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some()
            )

            val expected = DistributionSuffixError("17.0.1-tem", "tem").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }

        should("reject version with temurin suffix when using TEMURIN distribution") {
            val version = Version(
                candidate = "java",
                version = "17.0.1-temurin",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some()
            )

            val expected = DistributionSuffixError("17.0.1-temurin", "temurin").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }
    }
})