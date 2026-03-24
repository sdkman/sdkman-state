package io.sdkman.state.adapter.secondary.persistence

import arrow.core.None
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.withCleanDatabase
import java.time.Instant
import java.util.UUID

@Tags("integration")
class PostgresVendorRepositoryIntegrationSpec :
    ShouldSpec({

        val repo = PostgresVendorRepository()

        context("CRUD operations") {
            should("create a new vendor and find by email") {
                withCleanDatabase {
                    // given: a new vendor
                    val vendor =
                        Vendor(
                            id = UUID.randomUUID(),
                            email = "vendor@example.com",
                            password = "hashed-password",
                            candidates = listOf("java", "kotlin"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )

                    // when: upserting
                    val result = repo.upsert(vendor)

                    // then: vendor is persisted and findable
                    result.shouldBeRight()
                    val found = repo.findByEmail("vendor@example.com")
                    found.shouldBeRight()
                    found.getOrNull()!!.isSome() shouldBe true
                    found.getOrNull()!!.getOrNull()!!.email shouldBe "vendor@example.com"
                    found.getOrNull()!!.getOrNull()!!.candidates shouldBe listOf("java", "kotlin")
                }
            }

            should("find vendor by id") {
                withCleanDatabase {
                    val id = UUID.randomUUID()
                    val vendor =
                        Vendor(
                            id = id,
                            email = "byid@example.com",
                            password = "hashed-password",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    repo.upsert(vendor)

                    val found = repo.findById(id)
                    found.shouldBeRight()
                    found.getOrNull()!!.isSome() shouldBe true
                    found.getOrNull()!!.getOrNull()!!.id shouldBe id
                }
            }

            should("upsert updates existing vendor") {
                withCleanDatabase {
                    val vendor =
                        Vendor(
                            id = UUID.randomUUID(),
                            email = "update@example.com",
                            password = "old-hash",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    repo.upsert(vendor)

                    // when: upsert with new password and candidates
                    val updated = vendor.copy(password = "new-hash", candidates = listOf("java", "kotlin"))
                    val result = repo.upsert(updated)

                    // then: vendor is updated
                    result.shouldBeRight()
                    val found = repo.findByEmail("update@example.com")
                    found.getOrNull()!!.getOrNull()!!.password shouldBe "new-hash"
                    found.getOrNull()!!.getOrNull()!!.candidates shouldBe listOf("java", "kotlin")
                }
            }

            should("soft delete a vendor") {
                withCleanDatabase {
                    val id = UUID.randomUUID()
                    val vendor =
                        Vendor(
                            id = id,
                            email = "delete@example.com",
                            password = "hashed-password",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    repo.upsert(vendor)

                    val result = repo.softDelete(id)
                    result.shouldBeRight()
                    result.getOrNull()!!.isSome() shouldBe true
                    result
                        .getOrNull()!!
                        .getOrNull()!!
                        .deletedAt
                        .isSome() shouldBe true
                }
            }

            should("soft delete returns None for already-deleted vendor") {
                withCleanDatabase {
                    val id = UUID.randomUUID()
                    val vendor =
                        Vendor(
                            id = id,
                            email = "double-delete@example.com",
                            password = "hashed-password",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    repo.upsert(vendor)
                    repo.softDelete(id)

                    val result = repo.softDelete(id)
                    result.shouldBeRight()
                    result.getOrNull()!!.isNone() shouldBe true
                }
            }

            should("resurrect soft-deleted vendor via upsert") {
                withCleanDatabase {
                    val id = UUID.randomUUID()
                    val vendor =
                        Vendor(
                            id = id,
                            email = "resurrect@example.com",
                            password = "old-hash",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    repo.upsert(vendor)
                    repo.softDelete(id)

                    // when: upsert clears deleted_at
                    val resurrected = vendor.copy(password = "new-hash", deletedAt = None)
                    val result = repo.upsert(resurrected)

                    result.shouldBeRight()
                    val found = repo.findByEmail("resurrect@example.com")
                    found
                        .getOrNull()!!
                        .getOrNull()!!
                        .deletedAt
                        .isNone() shouldBe true
                    found.getOrNull()!!.getOrNull()!!.password shouldBe "new-hash"
                }
            }

            should("find all vendors") {
                withCleanDatabase {
                    val vendor1 =
                        Vendor(
                            id = UUID.randomUUID(),
                            email = "one@example.com",
                            password = "hash1",
                            candidates = listOf("java"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = None,
                        )
                    val vendor2 =
                        Vendor(
                            id = UUID.randomUUID(),
                            email = "two@example.com",
                            password = "hash2",
                            candidates = listOf("kotlin"),
                            createdAt = Instant.now(),
                            updatedAt = Instant.now(),
                            deletedAt = Instant.now().some(),
                        )
                    repo.upsert(vendor1)
                    repo.upsert(vendor2)

                    val result = repo.findAll()
                    result.shouldBeRight()
                    result.getOrNull()!!.size shouldBe 2
                }
            }
        }
    })
