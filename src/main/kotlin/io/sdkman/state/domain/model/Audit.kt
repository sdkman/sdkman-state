package io.sdkman.state.domain.model

sealed interface Auditable

enum class AuditOperation {
    CREATE,
    DELETE,
}
