package io.sdkman.state.application.validation

import arrow.core.*
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.UniqueVersion

object UniqueVersionValidator {
    fun validate(uniqueVersion: UniqueVersion): Either<ValidationError, UniqueVersion> =
        when {
            uniqueVersion.candidate.isBlank() -> EmptyFieldError("candidate").left()
            uniqueVersion.candidate == JAVA_CANDIDATE -> JavaRouteError(JAVA_DELETE_ROUTE).left()
            uniqueVersion.version.isBlank() -> EmptyFieldError("version").left()
            else -> uniqueVersion.right()
        }
}
