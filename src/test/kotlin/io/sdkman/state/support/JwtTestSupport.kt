package io.sdkman.state.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.time.Instant

const val TEST_JWT_SECRET = "test-jwt-secret-for-testing-only"
const val TEST_ADMIN_EMAIL = "admin@sdkman.io"
const val TEST_ADMIN_PASSWORD = "admin-password-123"

fun adminToken(): String =
    createTestToken(
        email = TEST_ADMIN_EMAIL,
        role = "admin",
        candidates = emptyList(),
    )

fun vendorToken(candidates: List<String> = listOf("java", "kotlin")): String =
    createTestToken(
        email = "vendor@example.com",
        role = "vendor",
        candidates = candidates,
    )

fun vendorTokenForEmail(
    email: String,
    candidates: List<String>,
): String =
    createTestToken(
        email = email,
        role = "vendor",
        candidates = candidates,
    )

fun expiredToken(): String =
    createTestToken(
        email = TEST_ADMIN_EMAIL,
        role = "admin",
        candidates = emptyList(),
        expiresAt = Instant.now().minusSeconds(3600),
    )

fun invalidSignatureToken(): String {
    val algorithm = Algorithm.HMAC256("wrong-secret")
    val now = Instant.now()
    return JWT
        .create()
        .withIssuer("sdkman-state")
        .withAudience("sdkman-state")
        .withSubject(TEST_ADMIN_EMAIL)
        .withClaim("role", "admin")
        .withIssuedAt(now)
        .withExpiresAt(now.plusSeconds(300))
        .sign(algorithm)
}

private fun createTestToken(
    email: String,
    role: String,
    candidates: List<String>,
    expiresAt: Instant = Instant.now().plusSeconds(300),
): String {
    val algorithm = Algorithm.HMAC256(TEST_JWT_SECRET)
    val now = Instant.now()
    val builder =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(email)
            .withClaim("role", role)
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
    if (candidates.isNotEmpty()) {
        builder.withClaim("candidates", candidates)
    }
    return builder.sign(algorithm)
}
