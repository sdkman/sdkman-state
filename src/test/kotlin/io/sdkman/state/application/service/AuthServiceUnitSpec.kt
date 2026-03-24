package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.None
import arrow.core.some
import at.favre.lib.crypto.bcrypt.BCrypt
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMinLength
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.sdkman.state.config.AppConfig
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import java.time.Instant
import java.util.UUID

class AuthServiceUnitSpec :
    ShouldSpec({

        val vendorRepo = mockk<VendorRepository>()
        val appConfig =
            mockk<AppConfig> {
                coEvery { adminEmail } returns "admin@sdkman.io"
                coEvery { adminPassword } returns "admin-password"
                coEvery { jwtSecret } returns "test-secret-for-jwt"
                coEvery { jwtExpiry } returns 3
            }
        val service = AuthServiceImpl(vendorRepo, appConfig)

        beforeEach { clearAllMocks() }

        // Re-set appConfig mocks since clearAllMocks clears them
        beforeEach {
            coEvery { appConfig.adminEmail } returns "admin@sdkman.io"
            coEvery { appConfig.adminPassword } returns "admin-password"
            coEvery { appConfig.jwtSecret } returns "test-secret-for-jwt"
            coEvery { appConfig.jwtExpiry } returns 3
        }

        context("authenticate") {

            should("return token for valid admin credentials") {
                val result = service.authenticate("admin@sdkman.io", "admin-password")

                result.shouldBeRight()
                result.getOrNull()!!.token.shouldHaveMinLength(10)
            }

            should("return Unauthorized for wrong admin password") {
                val result = service.authenticate("admin@sdkman.io", "wrong-password")

                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.Unauthorized>() }
            }

            should("return token for valid vendor credentials") {
                val hashedPassword = BCrypt.withDefaults().hashToString(12, "vendor-pass".toCharArray())
                val vendor =
                    Vendor(
                        id = UUID.randomUUID(),
                        email = "vendor@example.com",
                        password = hashedPassword,
                        candidates = listOf("java", "kotlin"),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        deletedAt = None,
                    )
                coEvery { vendorRepo.findByEmail("vendor@example.com") } returns Either.Right(vendor.some())

                val result = service.authenticate("vendor@example.com", "vendor-pass")

                result.shouldBeRight()
                result.getOrNull()!!.token.shouldHaveMinLength(10)
            }

            should("return Unauthorized for wrong vendor password") {
                val hashedPassword = BCrypt.withDefaults().hashToString(12, "correct-pass".toCharArray())
                val vendor =
                    Vendor(
                        id = UUID.randomUUID(),
                        email = "vendor@example.com",
                        password = hashedPassword,
                        candidates = listOf("java"),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        deletedAt = None,
                    )
                coEvery { vendorRepo.findByEmail("vendor@example.com") } returns Either.Right(vendor.some())

                val result = service.authenticate("vendor@example.com", "wrong-pass")

                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.Unauthorized>() }
            }

            should("return Unauthorized for soft-deleted vendor") {
                val hashedPassword = BCrypt.withDefaults().hashToString(12, "vendor-pass".toCharArray())
                val vendor =
                    Vendor(
                        id = UUID.randomUUID(),
                        email = "deleted@example.com",
                        password = hashedPassword,
                        candidates = listOf("java"),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        deletedAt = Instant.now().some(),
                    )
                coEvery { vendorRepo.findByEmail("deleted@example.com") } returns Either.Right(vendor.some())

                val result = service.authenticate("deleted@example.com", "vendor-pass")

                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.Unauthorized>() }
            }

            should("return Unauthorized for non-existent email") {
                coEvery { vendorRepo.findByEmail("nobody@example.com") } returns Either.Right(None)

                val result = service.authenticate("nobody@example.com", "whatever")

                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.Unauthorized>() }
            }
        }

        context("createOrUpdateVendor") {

            should("create new vendor with generated password") {
                coEvery { vendorRepo.findByEmail("new@example.com") } returns Either.Right(None)
                coEvery { vendorRepo.upsert(any()) } answers {
                    Either.Right(firstArg())
                }

                val result = service.createOrUpdateVendor("new@example.com", listOf("java"))

                result.shouldBeRight()
                val vwp = result.getOrNull()!!
                vwp.vendor.email shouldBe "new@example.com"
                vwp.vendor.candidates shouldBe listOf("java")
                vwp.plaintextPassword.shouldHaveMinLength(10)
            }

            should("update existing vendor with new password") {
                val existing =
                    Vendor(
                        id = UUID.randomUUID(),
                        email = "existing@example.com",
                        password = "old-hash",
                        candidates = listOf("java"),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        deletedAt = None,
                    )
                coEvery { vendorRepo.findByEmail("existing@example.com") } returns Either.Right(existing.some())
                coEvery { vendorRepo.upsert(any()) } answers {
                    Either.Right(firstArg())
                }

                val result = service.createOrUpdateVendor("existing@example.com", listOf("java", "kotlin"))

                result.shouldBeRight()
                val vwp = result.getOrNull()!!
                vwp.vendor.candidates shouldBe listOf("java", "kotlin")
            }
        }

        context("softDeleteVendor") {

            should("soft delete existing vendor") {
                val id = UUID.randomUUID()
                val vendor =
                    Vendor(
                        id = id,
                        email = "vendor@example.com",
                        password = "hash",
                        candidates = listOf("java"),
                        createdAt = Instant.now(),
                        updatedAt = Instant.now(),
                        deletedAt = Instant.now().some(),
                    )
                coEvery { vendorRepo.softDelete(id) } returns Either.Right(vendor.some())

                val result = service.softDeleteVendor(id)

                result.shouldBeRight()
                result.getOrNull()!!.id shouldBe id
            }

            should("return VendorNotFound for non-existent vendor") {
                val id = UUID.randomUUID()
                coEvery { vendorRepo.softDelete(id) } returns Either.Right(None)

                val result = service.softDeleteVendor(id)

                result.shouldBeLeft()
                result.onLeft { it.shouldBeInstanceOf<DomainError.VendorNotFound>() }
            }
        }

        context("listVendors") {

            should("return list of vendors") {
                val vendors =
                    listOf(
                        Vendor(
                            id = UUID.randomUUID(),
                            email = "v1@example.com",
                            password = "h1",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        ),
                    )
                coEvery { vendorRepo.findAll() } returns Either.Right(vendors)

                val result = service.listVendors()

                result.shouldBeRight()
                result.getOrNull()!!.size shouldBe 1
            }
        }
    })
