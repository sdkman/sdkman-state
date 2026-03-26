package io.sdkman.state.domain.repository

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable
import java.util.UUID

interface AuditRepository {
    suspend fun recordAudit(
        vendorId: UUID,
        email: String,
        operation: AuditOperation,
        data: Auditable,
    ): Either<DatabaseFailure, Unit>
}
