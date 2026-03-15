package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version

interface VersionRepository {
    suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): Either<DatabaseFailure, List<Version>>

    suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DatabaseFailure, Option<Version>>

    suspend fun createOrUpdate(version: Version): Either<DatabaseFailure, Int>

    suspend fun findVersionId(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Option<Int>>

    suspend fun findVersionIdByTag(
        candidate: String,
        tag: String,
        distribution: Option<Distribution>,
        platform: Platform,
    ): Either<DatabaseFailure, Option<Int>>

    suspend fun delete(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Int>
}
