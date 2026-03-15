package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version

interface VersionService {
    suspend fun findAll(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): List<Version>

    suspend fun findOne(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Option<Version>

    suspend fun createOrUpdate(
        version: Version,
        username: String,
    ): Either<DomainError, Unit>

    suspend fun delete(
        uniqueVersion: UniqueVersion,
        username: String,
    ): Either<DomainError, Unit>
}
