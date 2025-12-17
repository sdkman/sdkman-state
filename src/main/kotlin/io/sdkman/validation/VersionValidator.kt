package io.sdkman.validation

import arrow.core.*
import io.sdkman.domain.Version
import io.sdkman.domain.UniqueVersion

sealed class ValidationError(val message: String)
data class DistributionSuffixError(val version: String, val distribution: String) : ValidationError(
    "Version '$version' should not contain distribution '$distribution' suffix"
)
data class EmptyFieldError(val field: String) : ValidationError(
    "Field '$field' cannot be empty"
)
data class InvalidRequestError(val details: String) : ValidationError(
    "Invalid request: $details"
)

object VersionValidator {

    private val distributionSuffixPattern = Regex("-.+")

    fun validateVersion(version: Version): Either<ValidationError, Version> =
        if (version.version.contains(distributionSuffixPattern)) {
            val suffix = version.version.substringAfterLast('-')
            DistributionSuffixError(version.version, suffix).left()
        } else {
            version.right()
        }

    fun validateUniqueVersion(uniqueVersion: UniqueVersion): Either<ValidationError, UniqueVersion> =
        when {
            uniqueVersion.candidate.isBlank() -> EmptyFieldError("candidate").left()
            uniqueVersion.version.isBlank() -> EmptyFieldError("version").left()
            uniqueVersion.version.contains(distributionSuffixPattern) -> {
                val suffix = uniqueVersion.version.substringAfterLast('-')
                DistributionSuffixError(uniqueVersion.version, suffix).left()
            }
            else -> uniqueVersion.right()
        }
}