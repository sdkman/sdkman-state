package io.sdkman.state.application.validation

import arrow.core.*
import io.sdkman.state.domain.model.UniqueVersion

object UniqueVersionValidator {
    fun validate(uniqueVersion: UniqueVersion): Either<ValidationError, UniqueVersion> =
        when {
            uniqueVersion.candidate.isBlank() -> EmptyFieldError("candidate").left()
            uniqueVersion.version.isBlank() -> EmptyFieldError("version").left()
            else -> uniqueVersion.right()
        }
}
