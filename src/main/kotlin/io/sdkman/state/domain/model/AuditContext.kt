package io.sdkman.state.domain.model

import java.util.UUID

data class AuditContext(
    val vendorId: UUID,
    val email: String,
)

val NIL_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
