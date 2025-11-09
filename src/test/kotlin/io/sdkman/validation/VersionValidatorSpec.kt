package io.sdkman.validation

import arrow.core.None
import arrow.core.Some
import arrow.core.left
import arrow.core.right
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.domain.Platform
import io.sdkman.domain.Version

class VersionValidatorSpec : ShouldSpec({

    context("validateVersion") {
        
        should("accept version with no vendor") {
            val version = Version(
                candidate = "maven",
                version = "3.9.0",
                platform = Platform.UNIVERSAL,
                url = "https://example.com/maven-3.9.0.zip",
                visible = true.some(),
                vendor = None
            )
            
            VersionValidator.validateVersion(version) shouldBe version.right()
        }
        
        should("accept version with vendor when version does not contain vendor suffix") {
            val version = Version(
                candidate = "java",
                version = "17.0.1",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                vendor = Some("tem")
            )
            
            VersionValidator.validateVersion(version) shouldBe version.right()
        }
        
        should("reject version with vendor when version contains vendor suffix") {
            val version = Version(
                candidate = "java",
                version = "17.0.1-tem",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                vendor = Some("tem")
            )
            
            val expected = VendorSuffixError("17.0.1-tem", "tem").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }

        
        should("reject version with whitespace in vendor names") {
            val version = Version(
                candidate = "java",
                version = "17.0.1- tem",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                vendor = Some(" tem")
            )
            
            val expected = VendorSuffixError("17.0.1- tem", " tem").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }
        
        should("reject version with special characters in vendor names") {
            val version = Version(
                candidate = "java",
                version = "17.0.1-te.m",
                platform = Platform.LINUX_X64,
                url = "https://example.com/java-17.0.1.tar.gz",
                visible = true.some(),
                vendor = Some("te.m")
            )
            
            val expected = VendorSuffixError("17.0.1-te.m", "te.m").left()
            VersionValidator.validateVersion(version) shouldBe expected
        }
    }
})