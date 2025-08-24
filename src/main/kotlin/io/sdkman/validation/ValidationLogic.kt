package io.sdkman.validation

import arrow.core.*
import arrow.core.raise.either
import io.sdkman.domain.Version

sealed class ValidationError(val message: String)
data class VendorSuffixError(val version: String, val vendor: String) : ValidationError(
    "Version '$version' should not contain vendor '$vendor' suffix"
)

//TODO: Consider renaming this to something better
object ValidationLogic {
    
    fun validateVersion(version: Version): Either<ValidationError, Version> = either {
        //TODO: prefer using `map` and `getOrElse` over `fold` as per the rule kotlin.md
        version.vendor.fold(
            ifEmpty = { version },
            ifSome = { vendor ->
                if (version.version.endsWith("-$vendor")) {
                    //TODO: never raise errors, take it directly to an `Either.left()`
                    raise(VendorSuffixError(version.version, vendor))
                } else {
                    //TODO: return an `Either.right()`
                    version
                }
            }
        )
    }
}