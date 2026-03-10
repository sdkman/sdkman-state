package io.sdkman.validation

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.sdkman.domain.UniqueTag

object UniqueTagValidator {
    fun validate(uniqueTag: UniqueTag): Either<List<ValidationFailure>, UniqueTag> {
        val failures =
            buildList {
                if (uniqueTag.candidate.isBlank()) add(ValidationFailure("candidate", "Candidate must not be blank"))
                if (uniqueTag.tag.isBlank()) add(ValidationFailure("tag", "Tag must not be blank"))
            }
        return when {
            failures.isNotEmpty() -> failures.left()
            else -> uniqueTag.right()
        }
    }
}
