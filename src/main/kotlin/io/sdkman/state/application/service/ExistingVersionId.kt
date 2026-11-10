package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.flatMap
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.repository.VersionRepository

suspend fun VersionRepository.findExistingVersionId(uniqueVersion: UniqueVersion): Either<DomainError, Int> =
    findVersionId(uniqueVersion)
        .mapLeft { DomainError.DatabaseError(it) }
        .flatMap { versionId ->
            versionId.toEither { DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version) }
        }
