package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.VersionTag

interface TagRepository {
    suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>>

    suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>>

    suspend fun findTagNamesByVersionIds(versionIds: List<Int>): Either<DatabaseFailure, Map<Int, List<String>>>

    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>,
    ): Either<DatabaseFailure, Unit>

    suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int>

    suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean>
}
