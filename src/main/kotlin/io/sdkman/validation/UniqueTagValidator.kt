package io.sdkman.validation

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNone
import io.sdkman.domain.UniqueTag

object UniqueTagValidator {
    fun validate(uniqueTag: UniqueTag): Either<NonEmptyList<ValidationError>, UniqueTag> {
        val errors =
            buildList {
                if (uniqueTag.candidate.isBlank()) add(EmptyFieldError("candidate"))
                if (uniqueTag.tag.isBlank()) add(EmptyFieldError("tag"))
            }
        return errors
            .toNonEmptyListOrNone()
            .fold(
                { uniqueTag.right() },
                { nel -> nel.left() },
            )
    }
}
