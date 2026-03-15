package io.sdkman.domain

import arrow.core.Either
import arrow.core.Option

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
