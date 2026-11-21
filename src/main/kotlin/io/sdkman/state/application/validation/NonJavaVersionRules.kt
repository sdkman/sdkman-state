package io.sdkman.state.application.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Option
import arrow.core.flatMap
import arrow.core.left
import arrow.core.nel
import arrow.core.right

object NonJavaVersionRules {
    const val PATTERN_SOURCE = "^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$"
    val PATTERN = Regex(PATTERN_SOURCE)

    fun validate(version: Option<String>): Either<NonEmptyList<ValidationError>, String> =
        VersionFieldRules.validateVersion(version).flatMap { value ->
            if (PATTERN.matches(value)) {
                value.right()
            } else {
                InvalidNonJavaVersionError(version = value).nel().left()
            }
        }
}
