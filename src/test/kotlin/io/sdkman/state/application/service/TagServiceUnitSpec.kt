package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.TagRepository
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import java.util.UUID

private val NIL_UUID: UUID = UUID(0L, 0L)
private val VENDOR_UUID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

class TagServiceUnitSpec :
    ShouldSpec({
        val tagsRepo = mockk<TagRepository>()
        val auditRepo = mockk<AuditRepository>()
        val service = TagServiceImpl(tagsRepo, auditRepo)

        beforeEach { clearAllMocks() }

        context("replaceTags") {

            should("delegate to tag repository") {
                // given: repository replaces tags successfully
                coEvery {
                    tagsRepo.replaceTags(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts", "latest"))
                } returns Either.Right(Unit)

                // when: replacing tags
                val result =
                    service.replaceTags(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts", "latest"))

                // then: succeeds
                result.shouldBeRight()
                coVerify {
                    tagsRepo.replaceTags(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts", "latest"))
                }
            }

            should("return DatabaseError when repository fails") {
                // given: repository fails
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "tag error",
                        RuntimeException("constraint violation"),
                    )
                coEvery {
                    tagsRepo.replaceTags(42, "java", None, Platform.LINUX_X64, listOf("lts"))
                } returns Either.Left(dbFailure)

                // when: replacing tags
                val result = service.replaceTags(42, "java", None, Platform.LINUX_X64, listOf("lts"))

                // then: returns DatabaseError
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
            }
        }

        context("findTagNamesByVersionId") {

            should("delegate to tag repository") {
                // given: repository returns tag names
                coEvery { tagsRepo.findTagNamesByVersionId(42) } returns Either.Right(listOf("lts", "latest"))

                // when: finding tag names
                val result = service.findTagNamesByVersionId(42)

                // then: returns tag names
                result shouldBe Either.Right(listOf("lts", "latest"))
            }

            should("return DatabaseError when repository fails") {
                // given: repository fails
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection lost",
                        RuntimeException("timeout"),
                    )
                coEvery { tagsRepo.findTagNamesByVersionId(42) } returns Either.Left(dbFailure)

                // when: finding tag names
                val result = service.findTagNamesByVersionId(42)

                // then: returns DatabaseError
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
            }
        }

        context("deleteTag") {

            should("delete tag and record audit when tag exists") {
                // given: tag exists and delete succeeds
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "lts",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Right(1)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, uniqueTag)
                } returns Either.Right(Unit)

                // when: deleting the tag
                val result = service.deleteTag(uniqueTag, NIL_UUID, "admin")

                // then: succeeds and records audit
                result.shouldBeRight()
                coVerify { tagsRepo.deleteTag(uniqueTag) }
                coVerify { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, uniqueTag) }
            }

            should("return TagNotFound when tag does not exist") {
                // given: delete returns zero affected rows
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "nonexistent",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Right(0)

                // when: deleting a non-existent tag
                val result = service.deleteTag(uniqueTag, NIL_UUID, "admin")

                // then: returns TagNotFound
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.TagNotFound>()
                    error.tagName shouldBe "nonexistent"
                }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("return DatabaseError when tag deletion fails") {
                // given: database error during tag deletion
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "lts",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection reset",
                        RuntimeException("timeout"),
                    )
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Left(dbFailure)

                // when: deleting a tag when DB fails
                val result = service.deleteTag(uniqueTag, NIL_UUID, "admin")

                // then: returns DatabaseError wrapping the failure
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("still succeed when audit logging fails after tag deletion") {
                // given: tag delete succeeds but audit fails
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "lts",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Right(1)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, uniqueTag)
                } returns
                    Either.Left(
                        DatabaseFailure.QueryExecutionFailure(
                            "audit failed",
                            RuntimeException("disk full"),
                        ),
                    )

                // when: deleting a tag with failing audit
                val result = service.deleteTag(uniqueTag, NIL_UUID, "admin")

                // then: still succeeds (audit failure is logged but non-fatal)
                result.shouldBeRight()
            }

            should("pass correct vendor identity to audit record") {
                // given: a specific vendor identity
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "lts",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Right(1)
                coEvery {
                    auditRepo.recordAudit(VENDOR_UUID, "vendor@example.com", AuditOperation.DELETE, uniqueTag)
                } returns Either.Right(Unit)

                // when: deleting a tag as a specific vendor
                val result = service.deleteTag(uniqueTag, VENDOR_UUID, "vendor@example.com")

                // then: audit records the correct vendor identity
                result.shouldBeRight()
                coVerify {
                    auditRepo.recordAudit(VENDOR_UUID, "vendor@example.com", AuditOperation.DELETE, uniqueTag)
                }
            }
        }
    })
