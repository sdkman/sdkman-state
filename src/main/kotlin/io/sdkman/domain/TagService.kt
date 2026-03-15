package io.sdkman.domain

import arrow.core.Either

interface TagService {
    suspend fun deleteTag(
        uniqueTag: UniqueTag,
        username: String,
    ): Either<DomainError, Unit>
}
