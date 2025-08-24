package io.sdkman.validation

import arrow.core.*
import io.sdkman.domain.Version
import io.sdkman.domain.UniqueVersion

sealed class ValidationError(val message: String)
data class VendorSuffixError(val version: String, val vendor: String) : ValidationError(
    "Version '$version' should not contain vendor '$vendor' suffix"
)

object VersionValidator {
    
    fun validateVersion(version: Version): Either<ValidationError, Version> =
        version.vendor
            .map { vendor ->
                if (version.version.endsWith("-$vendor")) {
                    VendorSuffixError(version.version, vendor).left()
                } else {
                    version.right()
                }
            }
            .getOrElse { version.right() }
    
    fun validateUniqueVersion(uniqueVersion: UniqueVersion): Either<ValidationError, UniqueVersion> =
        uniqueVersion.vendor
            .map { vendor ->
                if (uniqueVersion.version.endsWith("-$vendor")) {
                    VendorSuffixError(uniqueVersion.version, vendor).left()
                } else {
                    uniqueVersion.right()
                }
            }
            .getOrElse { uniqueVersion.right() }
}