package io.sdkman.domain

import kotlinx.serialization.Serializable

sealed interface Auditable

@Serializable
enum class AuditOperation {
    CREATE,
    DELETE,
}
