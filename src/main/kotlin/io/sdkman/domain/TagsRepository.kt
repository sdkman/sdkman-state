package io.sdkman.domain

import arrow.core.Either
import arrow.core.Option

interface TagsRepository {
    suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>>

    suspend fun findVersionIdByTag(
        candidate: String,
        tag: String,
        distribution: Option<Distribution>,
        platform: Platform,
    ): Either<DatabaseFailure, Option<Int>>

    suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>,
    ): Either<DatabaseFailure, Unit>

    suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int>

    suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean>

    suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>>
}
