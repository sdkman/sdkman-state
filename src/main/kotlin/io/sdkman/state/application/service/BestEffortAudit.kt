package io.sdkman.state.application.service

import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable
import io.sdkman.state.domain.repository.AuditRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger: Logger = LoggerFactory.getLogger("io.sdkman.state.application.service.BestEffortAudit")

suspend fun AuditRepository.recordAuditBestEffort(
    vendorId: UUID,
    email: String,
    operation: AuditOperation,
    data: Auditable,
) {
    recordAudit(vendorId, email, operation, data).onLeft { error ->
        logger.warn("Audit logging failed for $operation: ${error.message}", error)
    }
}
