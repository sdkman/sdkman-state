package io.sdkman.state.domain.model

import arrow.core.Option
import java.time.Instant
import java.util.UUID

data class Vendor(
    val id: UUID,
    val email: String,
    val hashedPassword: String,
    val candidates: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Option<Instant>,
)
