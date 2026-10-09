package io.sdkman.state.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.sdkman.state.security.COMMUNITY_VENDOR_ID
import java.time.Instant
import java.util.UUID

object JwtTestSupport {
    const val TEST_SECRET = "test-jwt-secret-that-is-long-enough"
    const val ADMIN_EMAIL = "admin@sdkman.io"
    const val COMMUNITY_EMAIL = "community@sdkman.io"
    const val COMMUNITY_PASSWORD = "testcommunitypassword"
    val NIL_UUID: UUID = UUID(0L, 0L)

    private val algorithm = Algorithm.HMAC256(TEST_SECRET)

    fun adminToken(): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(ADMIN_EMAIL)
            .withClaim("role", "admin")
            .withClaim("vendor_id", NIL_UUID.toString())
            .withClaim("candidates", emptyList<String>())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(600))
            .sign(algorithm)

    fun communityToken(): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(COMMUNITY_EMAIL)
            .withClaim("role", "community")
            .withClaim("vendor_id", COMMUNITY_VENDOR_ID.toString())
            .withClaim("candidates", emptyList<String>())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(600))
            .sign(algorithm)

    fun vendorToken(
        vendorId: UUID = UUID.randomUUID(),
        email: String = "vendor@example.com",
        candidates: List<String> = emptyList(),
    ): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(email)
            .withClaim("role", "vendor")
            .withClaim("vendor_id", vendorId.toString())
            .withClaim("candidates", candidates)
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(600))
            .sign(algorithm)

    fun expiredToken(): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(ADMIN_EMAIL)
            .withClaim("role", "admin")
            .withClaim("vendor_id", NIL_UUID.toString())
            .withClaim("candidates", emptyList<String>())
            .withIssuedAt(Instant.now().minusSeconds(1200))
            .withExpiresAt(Instant.now().minusSeconds(600))
            .sign(algorithm)

    fun tokenWithWrongSecret(): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(ADMIN_EMAIL)
            .withClaim("role", "admin")
            .withClaim("vendor_id", NIL_UUID.toString())
            .withClaim("candidates", emptyList<String>())
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(600))
            .sign(Algorithm.HMAC256("wrong-secret"))

    fun tokenWithClaims(
        sub: String = ADMIN_EMAIL,
        role: String = "admin",
        vendorId: UUID = NIL_UUID,
        candidates: List<String> = emptyList(),
    ): String =
        JWT
            .create()
            .withIssuer("sdkman-state")
            .withAudience("sdkman-state")
            .withSubject(sub)
            .withClaim("role", role)
            .withClaim("vendor_id", vendorId.toString())
            .withClaim("candidates", candidates)
            .withIssuedAt(Instant.now())
            .withExpiresAt(Instant.now().plusSeconds(600))
            .sign(algorithm)
}
