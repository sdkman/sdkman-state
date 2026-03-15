package io.sdkman.domain

sealed interface Auditable

enum class AuditOperation {
    CREATE,
    DELETE,
}
