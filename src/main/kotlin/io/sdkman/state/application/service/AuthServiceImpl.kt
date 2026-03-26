package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.left
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.AuthError
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.domain.service.AuthService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private const val ISSUER = "sdkman-state"
private const val AUDIENCE = "sdkman-state"
private const val BCRYPT_COST = 12
private const val SECONDS_PER_MINUTE = 60L

class AuthServiceImpl(
    private val vendorRepository: VendorRepository,
    private val appConfig: AppConfig,
    private val rateLimiter: RateLimiter,
) : AuthService {
    private val logger = LoggerFactory.getLogger(AuthServiceImpl::class.java)

    private val adminHashedPassword: String =
        String(BCrypt.withDefaults().hash(BCRYPT_COST, appConfig.adminPassword.toByteArray()))

    private val dummyHash: String =
        String(BCrypt.withDefaults().hash(BCRYPT_COST, "dummy-password-for-timing".toByteArray()))

    override suspend fun login(
        email: String,
        password: String,
        clientIp: String,
    ): Either<AuthError, String> {
        if (rateLimiter.checkAndRecord(clientIp)) {
            return AuthError.RateLimitExceeded.left()
        }

        return if (email == appConfig.adminEmail) {
            verifyAdminLogin(email, password)
        } else {
            verifyVendorLogin(email, password)
        }
    }

    private fun verifyAdminLogin(
        email: String,
        password: String,
    ): Either<AuthError, String> {
        val result = BCrypt.verifyer().verify(password.toByteArray(), adminHashedPassword.toByteArray())
        return if (result.verified) {
            createToken(email, "admin", UUID(0L, 0L), emptyList())
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
            createToken(vendor.email, "vendor", vendor.id, vendor.candidates)
        } else {
            AuthError.InvalidCredentials.left()
        }
    }

    private fun verifyAgainstDummy(password: String) {
        BCrypt.verifyer().verify(password.toByteArray(), dummyHash.toByteArray())
    }

    private fun createToken(
        email: String,
        role: String,
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
                    .withClaim("role", role)
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
