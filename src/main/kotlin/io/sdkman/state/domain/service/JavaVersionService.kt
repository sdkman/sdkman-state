package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Version
import java.util.UUID

interface JavaVersionService {
    suspend fun publish(
        version: Version,
        vendorId: UUID,
        email: String,
    ): Either<DomainError, Unit>
}
