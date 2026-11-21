package io.sdkman.state.support

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.VersionPlatformRepository

fun failingVersionPlatforms(): VersionPlatformRepository =
    object : VersionPlatformRepository {
        override suspend fun findPlatforms(
            candidate: String,
            version: String,
            distribution: Option<Distribution>,
        ): Either<DatabaseFailure, List<Platform>> =
            DatabaseFailure
                .QueryExecutionFailure(
                    message = "Failed to find version platforms: connection refused",
                    cause = RuntimeException("connection refused"),
                ).left()
    }
