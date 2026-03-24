package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.None
import arrow.core.raise.either
import arrow.core.raise.ensure
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.TokenResponse
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.model.VendorWithPassword
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.domain.service.AuthService
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AuthServiceImpl(
    private val vendorRepository: VendorRepository,
    private val appConfig: AppConfig,
) : AuthService {
    private val secureRandom = SecureRandom()

    private val dummyHash: String =
        BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-password-for-timing".toCharArray())

    override suspend fun authenticate(
        email: String,
        password: String,
    ): Either<DomainError, TokenResponse> =
        either {
            if (email == appConfig.adminEmail) {
                val verified =
                    java.security.MessageDigest.isEqual(
                        password.toByteArray(),
                        appConfig.adminPassword.toByteArray(),
                    )
                ensure(verified) { DomainError.Unauthorized("Invalid credentials") }
                TokenResponse(
                    token = createToken(email = email, role = "admin", candidates = emptyList()),
                )
            } else {
                val vendor =
                    vendorRepository
                        .findByEmail(email)
                        .mapLeft { DomainError.DatabaseError(it) }
                        .bind()
                vendor.fold(
                    ifEmpty = {
                        BCrypt.verifyer().verify(password.toCharArray(), dummyHash.toCharArray())
                        raise(DomainError.Unauthorized("Invalid credentials"))
                    },
                    ifSome = { v ->
                        if (v.deletedAt.isSome()) {
                            BCrypt.verifyer().verify(password.toCharArray(), dummyHash.toCharArray())
                            raise(DomainError.Unauthorized("Invalid credentials"))
                        }
                        val verified = BCrypt.verifyer().verify(password.toCharArray(), v.password.toCharArray())
                        ensure(verified.verified) { DomainError.Unauthorized("Invalid credentials") }
                        TokenResponse(
                            token = createToken(email = email, role = "vendor", candidates = v.candidates),
                        )
                    },
                )
            }
        }

    override suspend fun createOrUpdateVendor(
        email: String,
        candidates: List<String>,
    ): Either<DomainError, VendorWithPassword> =
        either {
            val plaintextPassword = generatePassword()
            val hashedPassword = BCrypt.withDefaults().hashToString(BCRYPT_COST, plaintextPassword.toCharArray())
            val now = Instant.now()

            val existing =
                vendorRepository
                    .findByEmail(email)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()

            val vendorToUpsert =
                existing.fold(
                    ifEmpty = {
                        Vendor(
                            id = UUID.randomUUID(),
                            email = email,
                            password = hashedPassword,
                            candidates = candidates,
                            createdAt = now,
                            updatedAt = now,
                            deletedAt = None,
                        )
                    },
                    ifSome = { v ->
                        v.copy(
                            password = hashedPassword,
                            candidates = candidates.ifEmpty { v.candidates },
                            updatedAt = now,
                            deletedAt = None,
                        )
                    },
                )

            val saved =
                vendorRepository
                    .upsert(vendorToUpsert)
                    .mapLeft { DomainError.DatabaseError(it) }
                    .bind()

            VendorWithPassword(vendor = saved, plaintextPassword = plaintextPassword)
        }

    override suspend fun softDeleteVendor(id: UUID): Either<DomainError, Vendor> =
        either {
            vendorRepository
                .softDelete(id)
                .mapLeft { DomainError.DatabaseError(it) }
                .bind()
                .toEither { DomainError.VendorNotFound(id) }
                .bind()
        }

    override suspend fun listVendors(): Either<DomainError, List<Vendor>> =
        vendorRepository
            .findAll()
            .mapLeft { DomainError.DatabaseError(it) }

    private fun createToken(
        email: String,
        role: String,
        candidates: List<String>,
    ): String {
        val algorithm = Algorithm.HMAC256(appConfig.jwtSecret)
        val now = Instant.now()
        val builder =
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(email)
                .withClaim("role", role)
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(appConfig.jwtExpiry.toLong() * SECONDS_PER_MINUTE))
        if (candidates.isNotEmpty()) {
            builder.withClaim("candidates", candidates)
        }
        return builder.sign(algorithm)
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val ISSUER = "sdkman-state"
        const val AUDIENCE = "sdkman-state"
        private const val BCRYPT_COST = 12
        private const val PASSWORD_BYTES = 32
        private const val SECONDS_PER_MINUTE = 60L
    }
}
