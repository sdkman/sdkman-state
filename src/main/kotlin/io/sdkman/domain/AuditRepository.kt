package io.sdkman.domain

import arrow.core.Either

interface AuditRepository {
    suspend fun recordAudit(
        username: String,
        operation: AuditOperation,
        data: Auditable,
    ): Either<DatabaseFailure, Unit>
}
