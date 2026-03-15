package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.UniqueTag

interface TagService {
    suspend fun deleteTag(
        uniqueTag: UniqueTag,
        username: String,
    ): Either<DomainError, Unit>
}
