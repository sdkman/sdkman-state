package io.sdkman.domain

import kotlinx.serialization.Serializable
import kotlin.time.Instant

sealed interface Auditable

@Serializable
enum class AuditOperation {
    CREATE,
    DELETE,
}

@Serializable
data class AuditRecord(
    val id: Long = 0,
    val username: String,
    val timestamp: Instant,
    val operation: AuditOperation,
    val versionData: String,
)
