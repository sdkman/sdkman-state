package io.sdkman.validation

import arrow.core.*
import io.sdkman.domain.Version
import io.sdkman.domain.UniqueVersion

sealed class ValidationError(val message: String)
data class VendorSuffixError(val version: String, val vendor: String) : ValidationError(
    "Version '$version' should not contain vendor '$vendor' suffix"
)
data class EmptyFieldError(val field: String) : ValidationError(
    "Field '$field' cannot be empty"
)

object VersionValidator {
    
    private val vendorSuffixPattern = Regex("-.+")
    
    fun validateVersion(version: Version): Either<ValidationError, Version> =
        if (version.version.contains(vendorSuffixPattern)) {
            val suffix = version.version.substringAfterLast('-')
            VendorSuffixError(version.version, suffix).left()
        } else {
            version.right()
        }
    
    fun validateUniqueVersion(uniqueVersion: UniqueVersion): Either<ValidationError, UniqueVersion> =
        when {
            uniqueVersion.candidate.isBlank() -> EmptyFieldError("candidate").left()
            uniqueVersion.version.isBlank() -> EmptyFieldError("version").left()
            uniqueVersion.version.contains(vendorSuffixPattern) -> {
                val suffix = uniqueVersion.version.substringAfterLast('-')
                VendorSuffixError(uniqueVersion.version, suffix).left()
            }
            else -> uniqueVersion.right()
        }
}