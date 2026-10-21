package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.TagRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.support.passthroughTransactional
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.transactionalCheckingNoAuditYet
import io.sdkman.state.support.transactionalFailingWithoutRunning
import java.util.UUID

private val NIL_UUID: UUID = UUID(0L, 0L)
private val VENDOR_UUID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

class TagServiceUnitSpec :
    ShouldSpec({
        val tagsRepo = mockk<TagRepository>()
        val auditRepo = mockk<AuditRepository>()
        val versionsRepo = mockk<VersionRepository>()
        val service = TagServiceImpl(tagsRepo, auditRepo, versionsRepo, passthroughTransactional())

        beforeEach {
            clearAllMocks()
            coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Right(Unit)
        }

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
                    tagsRepo.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts"))
                } returns Either.Left(dbFailure)

                // when: replacing tags
                val result = service.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts"))

                // then: returns DatabaseError
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
            }
        }

        context("assignTagToVersion") {

            should("delegate to tag repository without recording an audit") {
                // given: repository assigns the tag successfully
                coEvery {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                } returns Either.Right(Unit)

                // when: assigning the tag to the version
                val result =
                    service.assignTagToVersion(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")

                // then: succeeds through the repository and leaves the audit to the caller
                result.shouldBeRight()
                coVerify {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("return DatabaseError when repository fails") {
                // given: repository fails
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "tag error",
                        RuntimeException("constraint violation"),
                    )
                coEvery {
                    tagsRepo.assignTag(42, "java", none(), Platform.LINUX_X64, "latest")
                } returns Either.Left(dbFailure)

                // when: assigning the tag to the version
                val result = service.assignTagToVersion(42, "java", none(), Platform.LINUX_X64, "latest")

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
            val temurinLts =
                UniqueTag(
                    candidate = "java",
                    tag = "lts",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                )

            fun givenDeletableTag(uniqueTag: UniqueTag) {
                coEvery { tagsRepo.deleteTag(uniqueTag) } returns Either.Right(1)
                coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Right(Unit)
            }

            should("delete tag and record audit when tag exists") {
                // given: tag exists and delete succeeds
                val uniqueTag =
                    UniqueTag(
                        candidate = "java",
                        tag = "lts",
                        distribution = none(),
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
                        distribution = none(),
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
                        distribution = none(),
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
                        distribution = none(),
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
                        distribution = none(),
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

            should("take the java write lock of the tag's scope before deleting a java tag") {
                // given: a TEMURIN java tag that exists
                givenDeletableTag(temurinLts)

                // when: deleting it
                service.deleteTag(temurinLts, NIL_UUID, "admin")

                // then: the java scope is locked before the tag is deleted
                coVerifyOrder {
                    versionsRepo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                    tagsRepo.deleteTag(temurinLts)
                }
            }

            should("never take the java write lock to delete a gradle tag") {
                // given: a gradle tag that exists
                val gradleLatest =
                    UniqueTag(
                        candidate = "gradle",
                        tag = "latest",
                        distribution = none(),
                        platform = Platform.UNIVERSAL,
                    )
                givenDeletableTag(gradleLatest)

                // when: deleting it
                service.deleteTag(gradleLatest, NIL_UUID, "admin")

                // then: no java scope is locked
                coVerify(exactly = 0) { versionsRepo.lockJavaWrites(any(), any()) }
            }

            should("answer DatabaseError and delete nothing when the java write lock fails") {
                // given: taking the java write lock fails
                val lockFailure = DatabaseFailure.QueryExecutionFailure("lock failed", RuntimeException("timeout"))
                coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Left(lockFailure)

                // when: deleting a java tag
                val result = service.deleteTag(temurinLts, NIL_UUID, "admin")

                // then: the failure surfaces before any tag is deleted
                result shouldBeLeft DomainError.DatabaseError(lockFailure)
                coVerify(exactly = 0) { tagsRepo.deleteTag(any()) }
            }

            should("make every repository call inside the transaction") {
                // given: a transaction that never runs its block and answers a database failure
                val txFailure = DomainError.DatabaseError(DatabaseFailure.QueryExecutionFailure("tx failed", RuntimeException("x")))
                val txService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo, transactionalFailingWithoutRunning(txFailure))

                // when: deleting a java tag
                val result = txService.deleteTag(temurinLts, NIL_UUID, "admin")

                // then: the failure surfaces and no repository was touched outside the block
                result shouldBeLeft txFailure
                coVerify { listOf(versionsRepo, tagsRepo) wasNot Called }
            }

            should("write the DELETE audit after the transaction ends") {
                // given: a transaction that checks, as its block returns, that no audit was written yet
                givenDeletableTag(temurinLts)
                val txService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo, transactionalCheckingNoAuditYet(auditRepo))

                // when: deleting a java tag
                txService.deleteTag(temurinLts, NIL_UUID, "admin")

                // then: the DELETE audit is written once the transaction is over
                coVerify(exactly = 1) { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, temurinLts) }
            }
        }

        context("assignTag") {
            val temurinLatest =
                TagAssignment(
                    candidate = "java",
                    version = "27.0.2",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                    tag = "latest",
                )

            fun givenAssignableVersion(assignment: TagAssignment) {
                val uniqueVersion =
                    UniqueVersion(
                        candidate = assignment.candidate,
                        version = assignment.version,
                        distribution = assignment.distribution,
                        platform = assignment.platform,
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery { tagsRepo.assignTag(42, any(), any(), any(), any()) } returns Either.Right(Unit)
                coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Right(Unit)
            }

            should("take the java write lock of the tag's scope before looking up a java version") {
                // given: a TEMURIN java version that exists
                givenAssignableVersion(temurinLatest)

                // when: assigning a tag to it
                service.assignTag(temurinLatest, NIL_UUID, "admin")

                // then: the java scope is locked before the version is looked up
                coVerifyOrder {
                    versionsRepo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                    versionsRepo.findVersionId(any())
                }
            }

            should("never take the java write lock to assign a gradle tag") {
                // given: a gradle version that exists
                val gradleLatest =
                    TagAssignment(
                        candidate = "gradle",
                        version = "8.14",
                        distribution = none(),
                        platform = Platform.UNIVERSAL,
                        tag = "latest",
                    )
                givenAssignableVersion(gradleLatest)

                // when: assigning a tag to it
                service.assignTag(gradleLatest, NIL_UUID, "admin")

                // then: no java scope is locked
                coVerify(exactly = 0) { versionsRepo.lockJavaWrites(any(), any()) }
            }

            should("answer DatabaseError and look up nothing when the java write lock fails") {
                // given: taking the java write lock fails
                val lockFailure = DatabaseFailure.QueryExecutionFailure("lock failed", RuntimeException("timeout"))
                coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Left(lockFailure)

                // when: assigning a tag to a java version
                val result = service.assignTag(temurinLatest, NIL_UUID, "admin")

                // then: the failure surfaces before the version is looked up
                result shouldBeLeft DomainError.DatabaseError(lockFailure)
                coVerify(exactly = 0) { versionsRepo.findVersionId(any()) }
            }

            should("make every repository call inside the transaction") {
                // given: a transaction that never runs its block and answers a database failure
                val txFailure = DomainError.DatabaseError(DatabaseFailure.QueryExecutionFailure("tx failed", RuntimeException("x")))
                val txService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo, transactionalFailingWithoutRunning(txFailure))

                // when: assigning a tag to a java version
                val result = txService.assignTag(temurinLatest, NIL_UUID, "admin")

                // then: the failure surfaces and no repository was touched outside the block
                result shouldBeLeft txFailure
                coVerify { listOf(versionsRepo, tagsRepo) wasNot Called }
            }

            should("write the TAG audit after the transaction ends") {
                // given: a transaction that checks, as its block returns, that no audit was written yet
                givenAssignableVersion(temurinLatest)
                val txService = TagServiceImpl(tagsRepo, auditRepo, versionsRepo, transactionalCheckingNoAuditYet(auditRepo))

                // when: assigning a tag to a java version
                txService.assignTag(temurinLatest, NIL_UUID, "admin")

                // then: the TAG audit is written once the transaction is over
                coVerify(exactly = 1) { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.TAG, temurinLatest) }
            }

            should("assign the tag, delegating to the repository and recording a TAG audit") {
                // given: the target version exists and the tag write + audit both succeed
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                } returns Either.Right(Unit)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.TAG, assignment)
                } returns Either.Right(Unit)

                // when: assigning the tag
                val result = service.assignTag(assignment, NIL_UUID, "admin")

                // then: succeeds, delegates to the repository, and records a TAG audit
                result.shouldBeRight()
                coVerify {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                }
                coVerify { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.TAG, assignment) }
            }

            should("return VersionNotFound when the target version does not exist") {
                // given: the target version cannot be resolved (no semverish validation gates the lookup)
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "99.0.0",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "99.0.0",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(none())

                // when: assigning the tag to the absent version
                val result = service.assignTag(assignment, NIL_UUID, "admin")

                // then: raises VersionNotFound without touching the tag write or audit
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.VersionNotFound>()
                    error.candidate shouldBe "java"
                    error.version shouldBe "99.0.0"
                }
                coVerify(exactly = 0) { tagsRepo.assignTag(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("still succeed when audit logging fails after the tag is assigned") {
                // given: the version exists and the tag write succeeds but the audit write fails
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                } returns Either.Right(Unit)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.TAG, assignment)
                } returns
                    Either.Left(
                        DatabaseFailure.QueryExecutionFailure(
                            "audit failed",
                            RuntimeException("disk full"),
                        ),
                    )

                // when: assigning the tag with a failing audit write
                val result = service.assignTag(assignment, NIL_UUID, "admin")

                // then: still succeeds (audit failure is logged but does not roll back the assignment)
                result.shouldBeRight()
            }

            should("return DatabaseError when the tag write fails") {
                // given: the version exists but the tag write fails (the 500 path)
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection reset",
                        RuntimeException("timeout"),
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery {
                    tagsRepo.assignTag(42, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")
                } returns Either.Left(dbFailure)

                // when: assigning the tag while the repository fails
                val result = service.assignTag(assignment, NIL_UUID, "admin")

                // then: surfaces DatabaseError and never records an audit
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("return DatabaseError when the version lookup fails") {
                // given: the version lookup itself fails before any tag write
                val assignment =
                    TagAssignment(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                        tag = "latest",
                    )
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "27.0.2",
                        distribution = Distribution.TEMURIN.some(),
                        platform = Platform.LINUX_X64,
                    )
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection lost",
                        RuntimeException("timeout"),
                    )
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Left(dbFailure)

                // when: assigning the tag while the version lookup fails
                val result = service.assignTag(assignment, NIL_UUID, "admin")

                // then: surfaces DatabaseError without touching the tag write or audit
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
                coVerify(exactly = 0) { tagsRepo.assignTag(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }
        }
    })
