package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version

interface VersionRepository {
    suspend fun read(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): List<Version>

    suspend fun read(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Option<Version>

    suspend fun create(cv: Version): Either<DatabaseFailure, Int>

    suspend fun findVersionId(uniqueVersion: UniqueVersion): Option<Int>

    suspend fun delete(version: UniqueVersion): Int
}
