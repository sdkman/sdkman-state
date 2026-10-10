package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.Some
import arrow.core.left
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.AuthError
import io.sdkman.state.domain.model.Role
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.domain.service.AuthService
import io.sdkman.state.security.BCRYPT_COST
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private const val ISSUER = "sdkman-state"
private const val AUDIENCE = "sdkman-state"
private const val SECONDS_PER_MINUTE = 60L
private val ADMIN_VENDOR_ID = UUID(0L, 0L)
private val COMMUNITY_VENDOR_ID = UUID(0L, 1L)

private class CommunityCredentials(
    val email: String,
    val hashedPassword: String,
)

class AuthServiceImpl(
    private val vendorRepository: VendorRepository,
    private val appConfig: AppConfig,
    private val rateLimiter: RateLimiter,
) : AuthService {
    private val logger = LoggerFactory.getLogger(AuthServiceImpl::class.java)

    private val adminHashedPassword: String = hash(appConfig.adminPassword)

    private val communityCredentials: Option<CommunityCredentials> =
        appConfig.communityAccount.map { CommunityCredentials(it.email, hash(it.password)) }

    private val dummyHash: String = hash("dummy-password-for-timing")

    override suspend fun login(
        email: String,
        password: String,
        clientIp: String,
    ): Either<AuthError, String> {
        if (rateLimiter.checkAndRecord(clientIp)) {
            return AuthError.RateLimitExceeded.left()
        }

        val community = communityCredentials.filter { it.email == email }
        return when {
            email == appConfig.adminEmail -> verifyAdminLogin(email, password)
            community is Some -> verifyCommunityLogin(community.value, password)
            else -> verifyVendorLogin(email, password)
        }
    }

    private fun verifyAdminLogin(
        email: String,
        password: String,
    ): Either<AuthError, String> {
        val result = BCrypt.verifyer().verify(password.toByteArray(), adminHashedPassword.toByteArray())
        return if (result.verified) {
            createToken(email, Role.ADMIN, ADMIN_VENDOR_ID, emptyList())
        } else {
            AuthError.InvalidCredentials.left()
        }
    }

    private fun verifyCommunityLogin(
        credentials: CommunityCredentials,
        password: String,
    ): Either<AuthError, String> {
        val result = BCrypt.verifyer().verify(password.toByteArray(), credentials.hashedPassword.toByteArray())
        return if (result.verified) {
            createToken(credentials.email, Role.COMMUNITY, COMMUNITY_VENDOR_ID, emptyList())
        } else {
            AuthError.InvalidCredentials.left()
        }
    }

    private suspend fun verifyVendorLogin(
        email: String,
        password: String,
    ): Either<AuthError, String> {
        val vendorResult = vendorRepository.findByEmail(email)
        return vendorResult.fold(
            ifLeft = {
                logger.warn("Database error during login: ${it.message}")
                verifyAgainstDummy(password)
                AuthError.InvalidCredentials.left()
            },
            ifRight = { optionalVendor ->
                optionalVendor.fold(
                    ifEmpty = {
                        verifyAgainstDummy(password)
                        AuthError.InvalidCredentials.left()
                    },
                    ifSome = { vendor -> verifyVendorCredentials(vendor, password) },
                )
            },
        )
    }

    private fun verifyVendorCredentials(
        vendor: Vendor,
        password: String,
    ): Either<AuthError, String> {
        val isDeleted = vendor.deletedAt.isSome()
        val hashToVerify = if (isDeleted) dummyHash else vendor.hashedPassword
        val result = BCrypt.verifyer().verify(password.toByteArray(), hashToVerify.toByteArray())
        return if (result.verified && !isDeleted) {
            createToken(vendor.email, Role.VENDOR, vendor.id, vendor.candidates)
        } else {
            AuthError.InvalidCredentials.left()
        }
    }

    private fun verifyAgainstDummy(password: String) {
        BCrypt.verifyer().verify(password.toByteArray(), dummyHash.toByteArray())
    }

    private fun hash(password: String): String = String(BCrypt.withDefaults().hash(BCRYPT_COST, password.toByteArray()))

    private fun createToken(
        email: String,
        role: Role,
        vendorId: UUID,
        candidates: List<String>,
    ): Either<AuthError, String> =
        Either
            .catch {
                val now = Instant.now()
                val expiresAt = now.plusSeconds(appConfig.jwtExpiry * SECONDS_PER_MINUTE)
                JWT
                    .create()
                    .withIssuer(ISSUER)
                    .withAudience(AUDIENCE)
                    .withSubject(email)
                    .withClaim("role", role.claim)
                    .withClaim("vendor_id", vendorId.toString())
                    .withClaim("candidates", candidates)
                    .withIssuedAt(now)
                    .withExpiresAt(expiresAt)
                    .sign(Algorithm.HMAC256(appConfig.jwtSecret))
            }.mapLeft {
                logger.error("JWT creation failed", it)
                AuthError.TokenCreationFailed
            }
}
