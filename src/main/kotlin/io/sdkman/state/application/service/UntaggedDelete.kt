package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.VersionRepository

suspend fun VersionRepository.deleteUntagged(uniqueVersion: UniqueVersion): Either<DomainError, Version> =
    either {
        lockWritesIfJava(uniqueVersion.candidate, uniqueVersion.distribution).bind()
        val versionToDelete =
            findUnique(
                candidate = uniqueVersion.candidate,
                version = uniqueVersion.version,
                platform = uniqueVersion.platform,
                distribution = uniqueVersion.distribution,
            ).mapLeft { DomainError.DatabaseError(it) }
                .bind()
                .toEither {
                    DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version)
                }.bind()
        val tagNames = versionToDelete.tags.getOrElse { emptyList() }
        if (tagNames.isNotEmpty()) raise(DomainError.TagConflict(tagNames))
        val deleted =
            delete(uniqueVersion)
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
        if (deleted == 0) {
            raise(DomainError.VersionNotFound(uniqueVersion.candidate, uniqueVersion.version))
        }
        versionToDelete
    }
