package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.right
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.VersionRepository

suspend fun VersionRepository.lockWritesIfJava(
    candidate: String,
    distribution: Option<Distribution>,
    platform: Platform,
): Either<DomainError, Unit> =
    if (candidate == JAVA_CANDIDATE) {
        lockJavaWrites(distribution, platform).mapLeft { DomainError.DatabaseError(it) }
    } else {
        Unit.right()
    }
