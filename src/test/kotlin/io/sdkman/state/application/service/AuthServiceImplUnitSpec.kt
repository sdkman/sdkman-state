package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.None
import arrow.core.some
import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.AuthError
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.security.BCRYPT_COST
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import java.time.Instant
import java.util.UUID

private const val JWT_SECRET = "test-secret-for-unit-tests"
private const val ADMIN_EMAIL = "admin@test.com"
private const val ADMIN_PASSWORD = "admin-password"

class AuthServiceImplUnitSpec :
    ShouldSpec({
        val vendorRepo = mockk<VendorRepository>()
        val rateLimiter = mockk<RateLimiter>()
        val appConfig =
            mockk<AppConfig> {
                every { adminEmail } returns ADMIN_EMAIL
                every { adminPassword } returns ADMIN_PASSWORD
                every { jwtSecret } returns JWT_SECRET
                every { jwtExpiry } returns 10
            }
        val service = AuthServiceImpl(vendorRepo, appConfig, rateLimiter)

        beforeEach { clearAllMocks() }

        // Re-stub appConfig defaults after clearAllMocks
        beforeEach {
            every { appConfig.adminEmail } returns ADMIN_EMAIL
            every { appConfig.adminPassword } returns ADMIN_PASSWORD
            every { appConfig.jwtSecret } returns JWT_SECRET
            every { appConfig.jwtExpiry } returns 10
            every { rateLimiter.checkAndRecord(any()) } returns false
        }

        should("return JWT with admin role for valid admin login") {
            // when
            val result = service.login(ADMIN_EMAIL, ADMIN_PASSWORD, "127.0.0.1")

            // then
            val token = result.shouldBeRight()
            val decoded = JWT.require(Algorithm.HMAC256(JWT_SECRET)).build().verify(token)
            decoded.subject shouldBe ADMIN_EMAIL
            decoded.getClaim("role").asString() shouldBe "admin"
            decoded.issuer shouldBe "sdkman-state"
            decoded.audience shouldContainExactly listOf("sdkman-state")
        }

        should("return JWT with vendor role for valid vendor login") {
            // given
            val vendorId = UUID.randomUUID()
            val hashedPassword = String(BCrypt.withDefaults().hash(BCRYPT_COST, "vendor-pw".toByteArray()))
            val vendor =
                Vendor(
                    id = vendorId,
                    email = "vendor@test.com",
                    hashedPassword = hashedPassword,
                    candidates = listOf("java", "kotlin"),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    deletedAt = None,
                )
            coEvery { vendorRepo.findByEmail("vendor@test.com") } returns Either.Right(vendor.some())

            // when
            val result = service.login("vendor@test.com", "vendor-pw", "127.0.0.1")

            // then
            val token = result.shouldBeRight()
            val decoded = JWT.require(Algorithm.HMAC256(JWT_SECRET)).build().verify(token)
            decoded.subject shouldBe "vendor@test.com"
            decoded.getClaim("role").asString() shouldBe "vendor"
            decoded.getClaim("vendor_id").asString() shouldBe vendorId.toString()
            decoded.getClaim("candidates").asList(String::class.java) shouldContainExactly listOf("java", "kotlin")
        }

        should("return InvalidCredentials for wrong admin password") {
            // when
            val result = service.login(ADMIN_EMAIL, "wrong-password", "127.0.0.1")

            // then
            result.shouldBeLeft().shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        should("return InvalidCredentials for wrong vendor password") {
            // given
            val hashedPassword = String(BCrypt.withDefaults().hash(BCRYPT_COST, "correct-pw".toByteArray()))
            val vendor =
                Vendor(
                    id = UUID.randomUUID(),
                    email = "vendor@test.com",
                    hashedPassword = hashedPassword,
                    candidates = listOf("java"),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    deletedAt = None,
                )
            coEvery { vendorRepo.findByEmail("vendor@test.com") } returns Either.Right(vendor.some())

            // when
            val result = service.login("vendor@test.com", "wrong-pw", "127.0.0.1")

            // then
            result.shouldBeLeft().shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        should("return InvalidCredentials for non-existent email") {
            // given
            coEvery { vendorRepo.findByEmail("unknown@test.com") } returns Either.Right(None)

            // when
            val result = service.login("unknown@test.com", "any-pw", "127.0.0.1")

            // then
            result.shouldBeLeft().shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        should("return InvalidCredentials for soft-deleted vendor") {
            // given
            val hashedPassword = String(BCrypt.withDefaults().hash(BCRYPT_COST, "vendor-pw".toByteArray()))
            val vendor =
                Vendor(
                    id = UUID.randomUUID(),
                    email = "deleted@test.com",
                    hashedPassword = hashedPassword,
                    candidates = listOf("java"),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    deletedAt = Instant.now().some(),
                )
            coEvery { vendorRepo.findByEmail("deleted@test.com") } returns Either.Right(vendor.some())

            // when
            val result = service.login("deleted@test.com", "vendor-pw", "127.0.0.1")

            // then
            result.shouldBeLeft().shouldBeInstanceOf<AuthError.InvalidCredentials>()
        }

        should("return RateLimitExceeded when rate limiter blocks") {
            // given
            every { rateLimiter.checkAndRecord("1.2.3.4") } returns true

            // when
            val result = service.login(ADMIN_EMAIL, ADMIN_PASSWORD, "1.2.3.4")

            // then
            result.shouldBeLeft().shouldBeInstanceOf<AuthError.RateLimitExceeded>()
        }
    })
