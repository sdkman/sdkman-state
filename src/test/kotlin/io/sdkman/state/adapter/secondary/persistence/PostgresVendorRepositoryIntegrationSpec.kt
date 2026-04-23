package io.sdkman.state.adapter.secondary.persistence

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresVendorRepositoryIntegrationSpec :
    ShouldSpec({
        val repo = PostgresVendorRepository()

        should("create a new vendor via upsert") {
            withCleanDatabase {
                // when
                val result = repo.upsert("vendor@example.com", "hashed-pw", listOf("java", "kotlin").some())

                // then
                val (vendor, created) = result.shouldBeRight()
                created shouldBe true
                vendor.email shouldBe "vendor@example.com"
                vendor.hashedPassword shouldBe "hashed-pw"
                vendor.candidates shouldContainExactlyInAnyOrder listOf("java", "kotlin")
                vendor.deletedAt shouldBe none()
            }
        }

        should("find vendor by email") {
            withCleanDatabase {
                // given
                repo.upsert("find@example.com", "hashed-pw", listOf("java").some())

                // when
                val result = repo.findByEmail("find@example.com")

                // then
                val vendor = result.shouldBeRight()
                vendor.shouldBeSome().email shouldBe "find@example.com"
            }
        }

        should("return none() for non-existent email") {
            withCleanDatabase {
                // when
                val result = repo.findByEmail("nonexistent@example.com")

                // then
                val vendor = result.shouldBeRight()
                vendor.isNone() shouldBe true
            }
        }

        should("find vendor by id") {
            withCleanDatabase {
                // given
                val (created, _) = repo.upsert("byid@example.com", "hashed-pw", listOf("java").some()).shouldBeRight()

                // when
                val result = repo.findById(created.id)

                // then
                val vendor = result.shouldBeRight()
                vendor.shouldBeSome().email shouldBe "byid@example.com"
            }
        }

        should("find all active vendors excluding deleted") {
            withCleanDatabase {
                // given
                val (v1, _) = repo.upsert("active@example.com", "pw1", listOf("java").some()).shouldBeRight()
                repo.upsert("deleted@example.com", "pw2", listOf("kotlin").some())
                repo.softDelete(
                    repo
                        .findByEmail("deleted@example.com")
                        .shouldBeRight()
                        .shouldBeSome()
                        .id,
                )

                // when
                val result = repo.findAll(includeDeleted = false)

                // then
                val vendors = result.shouldBeRight()
                vendors shouldHaveSize 1
                vendors.first().email shouldBe "active@example.com"
            }
        }

        should("find all vendors including deleted") {
            withCleanDatabase {
                // given
                repo.upsert("active2@example.com", "pw1", listOf("java").some())
                repo.upsert("deleted2@example.com", "pw2", listOf("kotlin").some())
                repo.softDelete(
                    repo
                        .findByEmail("deleted2@example.com")
                        .shouldBeRight()
                        .shouldBeSome()
                        .id,
                )

                // when
                val result = repo.findAll(includeDeleted = true)

                // then
                val vendors = result.shouldBeRight()
                vendors shouldHaveSize 2
            }
        }

        should("soft delete a vendor") {
            withCleanDatabase {
                // given
                val (created, _) = repo.upsert("todelete@example.com", "pw", listOf("java").some()).shouldBeRight()

                // when
                val result = repo.softDelete(created.id)

                // then
                val deleted = result.shouldBeRight()
                deleted.shouldBeSome().deletedAt.shouldBeSome()
            }
        }

        should("return none() when soft deleting already-deleted vendor") {
            withCleanDatabase {
                // given
                val (created, _) = repo.upsert("alreadydel@example.com", "pw", listOf("java").some()).shouldBeRight()
                repo.softDelete(created.id)

                // when
                val result = repo.softDelete(created.id)

                // then
                val maybeVendor = result.shouldBeRight()
                maybeVendor.isNone() shouldBe true
            }
        }

        should("update existing vendor on upsert") {
            withCleanDatabase {
                // given
                repo.upsert("update@example.com", "old-pw", listOf("java").some())

                // when
                val result = repo.upsert("update@example.com", "new-pw", listOf("kotlin", "groovy").some())

                // then
                val (vendor, created) = result.shouldBeRight()
                created shouldBe false
                vendor.hashedPassword shouldBe "new-pw"
                vendor.candidates shouldContainExactlyInAnyOrder listOf("kotlin", "groovy")
            }
        }

        should("resurrect soft-deleted vendor on upsert") {
            withCleanDatabase {
                // given
                val (created, _) =
                    repo.upsert("resurrect@example.com", "old-pw", listOf("java").some()).shouldBeRight()
                repo.softDelete(created.id)

                // when
                val result = repo.upsert("resurrect@example.com", "new-pw", listOf("kotlin").some())

                // then
                val (vendor, wasCreated) = result.shouldBeRight()
                wasCreated shouldBe false
                vendor.deletedAt shouldBe none()
                vendor.hashedPassword shouldBe "new-pw"
                vendor.candidates shouldContainExactlyInAnyOrder listOf("kotlin")
            }
        }

        should("preserve candidates when upsert omits candidates") {
            withCleanDatabase {
                // given
                repo.upsert("preserve@example.com", "pw", listOf("java", "kotlin").some())

                // when
                val result = repo.upsert("preserve@example.com", "new-pw", none())

                // then
                val (vendor, _) = result.shouldBeRight()
                vendor.candidates shouldContainExactlyInAnyOrder listOf("java", "kotlin")
                vendor.hashedPassword shouldBe "new-pw"
            }
        }
    })
