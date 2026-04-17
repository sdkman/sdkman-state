package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import java.util.UUID

interface VersionService {
    suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): Either<DomainError, List<Version>>

    suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DomainError, Option<Version>>

    suspend fun resolveByTag(
        candidate: String,
        tag: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DomainError, Option<Version>>

    suspend fun createOrUpdate(
        version: Version,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit>

    suspend fun delete(
        uniqueVersion: UniqueVersion,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit>
}
