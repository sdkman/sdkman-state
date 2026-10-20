package io.sdkman.state.application.validation

import arrow.core.Either
import io.sdkman.state.domain.model.JavaVersion

object SemverishValidator {
    fun validate(version: String): Either<ValidationError, String> =
        JavaVersion
            .parse(version)
            .map { it.spelling }
            .toEither { InvalidVersionFormatError(version = version) }
}
