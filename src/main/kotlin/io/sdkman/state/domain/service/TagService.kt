package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag

interface TagService {
    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>,
    ): Either<DomainError, Unit>

    suspend fun findTagNamesByVersionId(versionId: Int): Either<DomainError, List<String>>

    suspend fun deleteTag(
        uniqueTag: UniqueTag,
        username: String,
    ): Either<DomainError, Unit>
}
