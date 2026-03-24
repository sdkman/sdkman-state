package io.sdkman.state.config

import io.ktor.server.auth.*
import java.util.UUID

@Suppress("DEPRECATION")
data class JwtPrincipal(
    val vendorId: UUID,
    val email: String,
    val role: String,
    val candidates: List<String>,
) : Principal
