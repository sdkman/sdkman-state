package io.sdkman.state.domain.repository

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.AuditContext
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable

interface AuditRepository {
    suspend fun recordAudit(
        context: AuditContext,
        operation: AuditOperation,
        data: Auditable,
    ): Either<DatabaseFailure, Unit>
}
