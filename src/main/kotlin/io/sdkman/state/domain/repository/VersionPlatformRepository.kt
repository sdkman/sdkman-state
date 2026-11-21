package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform

interface VersionPlatformRepository {
    suspend fun findPlatforms(
        candidate: String,
        version: String,
        distribution: Option<Distribution>,
    ): Either<DatabaseFailure, List<Platform>>
}
