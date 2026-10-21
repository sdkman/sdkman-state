package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.Option
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
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.domain.service.Transactional
import io.sdkman.state.support.passthroughTransactional
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import java.util.UUID

private val NIL_UUID: UUID = UUID(0L, 0L)

class VersionServiceUnitSpec :
    ShouldSpec({
        val versionsRepo = mockk<VersionRepository>()
        val tagService = mockk<TagService>()
        val auditRepo = mockk<AuditRepository>()
        val service = VersionServiceImpl(versionsRepo, tagService, auditRepo, passthroughTransactional())

        beforeEach {
            clearAllMocks()
            coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Right(Unit)
        }

        context("findByCandidate") {

            should("delegate to version repository") {
                // given: repository returns a list of versions
                val versions =
                    listOf(
                        Version(
                            candidate = "java",
                            version = "17.0.1",
                            platform = Platform.LINUX_X64,
                            url = "https://example.com/java-17.tar.gz",
                        ),
                    )
                coEvery {
                    versionsRepo.findByCandidate("java", any<Option<Platform>>(), any(), any())
                } returns Either.Right(versions)

                // when: finding all versions for a candidate
                val result = service.findByCandidate("java", none(), none(), none())

                // then: returns the list from the repository
                result shouldBe Either.Right(versions)
            }

            should("pass filter parameters to repository") {
                // given: repository returns filtered results
                coEvery {
                    versionsRepo.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        none(),
                        true.some(),
                    )
                } returns Either.Right(emptyList())

                // when: finding versions with filters
                val result =
                    service.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        none(),
                        true.some(),
                    )

                // then: returns empty list and passes filters correctly
                result shouldBe Either.Right(emptyList())
                coVerify {
                    versionsRepo.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        none(),
                        true.some(),
                    )
                }
            }
        }

        context("findUnique") {

            should("return version when found") {
                // given: repository finds a version
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, none())
                } returns Either.Right(version.some())

                // when: looking up a specific version
                val result =
                    service.findUnique("java", "17.0.1", Platform.LINUX_X64, none())

                // then: returns the version
                result shouldBe Either.Right(version.some())
            }

            should("return none() when version not found") {
                // given: repository does not find the version
                coEvery {
                    versionsRepo.findUnique("java", "99.0.0", Platform.LINUX_X64, none())
                } returns Either.Right(none())

                // when: looking up a non-existent version
                val result =
                    service.findUnique("java", "99.0.0", Platform.LINUX_X64, none())

                // then: returns none()
                result shouldBe Either.Right(none())
            }
        }

        context("createOrUpdate") {

            should("create version, record audit, and process tags") {
                // given: a version with tags
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                        tags = listOf("lts", "latest").some(),
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.CREATE, version)
                } returns Either.Right(Unit)
                coEvery {
                    tagService.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts", "latest"))
                } returns Either.Right(Unit)

                // when: creating a version
                val result = service.createOrUpdate(version, NIL_UUID, "admin")

                // then: succeeds and calls all three operations
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(version) }
                coVerify { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.CREATE, version) }
                coVerify {
                    tagService.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts", "latest"))
                }
            }

            should("create version without tags when tags are none()") {
                // given: a version without tags
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.CREATE, version)
                } returns Either.Right(Unit)

                // when: creating a version without tags
                val result = service.createOrUpdate(version, NIL_UUID, "admin")

                // then: succeeds and does not call replaceTags
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(version) }
                coVerify { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.CREATE, version) }
                coVerify(exactly = 0) { tagService.replaceTags(any(), any(), any(), any(), any()) }
            }

            should("return DatabaseError when repository create fails") {
                // given: repository create fails
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "duplicate key",
                        RuntimeException("constraint violation"),
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Left(dbFailure)

                // when: creating a version that triggers a DB error
                val result = service.createOrUpdate(version, NIL_UUID, "admin")

                // then: returns a DatabaseError wrapping the failure
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
                coVerify { tagService wasNot Called }
            }

            should("still succeed when audit logging fails") {
                // given: audit logging fails but create succeeds
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.CREATE, version)
                } returns
                    Either.Left(
                        DatabaseFailure.QueryExecutionFailure(
                            "audit table full",
                            RuntimeException("disk full"),
                        ),
                    )

                // when: creating a version with failing audit
                val result = service.createOrUpdate(version, NIL_UUID, "admin")

                // then: still succeeds (audit failure is logged but non-fatal)
                result.shouldBeRight()
            }

            should("return DatabaseError and skip audit when tag processing fails") {
                // R5: tag replacement is atomic with the version write — if tags fail, the
                // version write rolls back and the request must surface the failure (no more
                // silent swallow). Audit must also be skipped because the version write was
                // rolled back, so there is no successful operation to record.
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                        tags = listOf("lts").some(),
                    )
                val tagFailure =
                    DomainError.DatabaseError(
                        DatabaseFailure.QueryExecutionFailure(
                            "tag error",
                            RuntimeException("tag failure"),
                        ),
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    tagService.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts"))
                } returns Either.Left(tagFailure)

                // when: creating a version with failing tag processing
                val result = service.createOrUpdate(version, NIL_UUID, "admin")

                // then: surfaces the tag failure and never touches audit
                result.shouldBeLeft()
                result.onLeft { it shouldBe tagFailure }
                coVerify(exactly = 0) {
                    auditRepo.recordAudit(any(), any(), any(), any())
                }
            }

            should("roll back the outer transaction when tag processing fails") {
                // R5: the version+tag write must run in one transaction. Verify by spying on the
                // Transactional port — it should be called exactly once, and a Left bubbled out
                // of its block must reach the caller unchanged.
                val transactional = mockk<Transactional>()
                val txService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional)
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                        tags = listOf("lts").some(),
                    )
                val tagFailure =
                    DomainError.DatabaseError(
                        DatabaseFailure.QueryExecutionFailure("tag error", RuntimeException("x")),
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    tagService.replaceTags(42, "java", none(), Platform.LINUX_X64, listOf("lts"))
                } returns Either.Left(tagFailure)
                coEvery { transactional.inTransaction<DomainError, Unit>(any()) } coAnswers {
                    val block = firstArg<suspend () -> Either<DomainError, Unit>>()
                    block()
                }

                // when: the version write succeeds but tag replacement fails
                val result = txService.createOrUpdate(version, NIL_UUID, "admin")

                // then: the failure is returned and inTransaction was invoked exactly once
                result.shouldBeLeft()
                coVerify(exactly = 1) { transactional.inTransaction<DomainError, Unit>(any()) }
            }
        }

        context("delete") {
            val temurinRow =
                UniqueVersion(
                    candidate = JAVA_CANDIDATE,
                    version = "25.0.4.1+1",
                    distribution = Distribution.TEMURIN.some(),
                    platform = Platform.LINUX_X64,
                )

            fun givenStoredRow(
                uniqueVersion: UniqueVersion,
                tags: Option<List<String>> = emptyList<String>().some(),
            ): Version {
                val row =
                    Version(
                        candidate = uniqueVersion.candidate,
                        version = uniqueVersion.version,
                        platform = uniqueVersion.platform,
                        url = "https://example.com/${uniqueVersion.candidate}-${uniqueVersion.version}.tar.gz",
                        distribution = uniqueVersion.distribution,
                        tags = tags,
                    )
                coEvery {
                    versionsRepo.findUnique(
                        uniqueVersion.candidate,
                        uniqueVersion.version,
                        uniqueVersion.platform,
                        uniqueVersion.distribution,
                    )
                } returns Either.Right(row.some())
                return row
            }

            fun givenDeletableRow(
                uniqueVersion: UniqueVersion,
                tags: Option<List<String>> = emptyList<String>().some(),
            ): Version =
                givenStoredRow(uniqueVersion, tags).also {
                    coEvery { versionsRepo.delete(uniqueVersion) } returns Either.Right(1)
                    coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Right(Unit)
                }

            should("delete version when it exists and has no tags") {
                // given: version exists with an empty tag list (as findUnique populates after fetch)
                val row = givenDeletableRow(temurinRow)

                // when: deleting the version
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: succeeds via a single findUnique + delete pair (no redundant id/tag lookups)
                result.shouldBeRight()
                coVerify(ordering = io.mockk.Ordering.ORDERED) {
                    versionsRepo.findUnique(JAVA_CANDIDATE, "25.0.4.1+1", Platform.LINUX_X64, Distribution.TEMURIN.some())
                    versionsRepo.delete(temurinRow)
                    auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, row)
                }
                coVerify(exactly = 0) { versionsRepo.findVersionId(any()) }
                coVerify(exactly = 0) { tagService.findTagNamesByVersionId(any()) }
            }

            should("treat findUnique's None tags as no-tag for the conflict check") {
                // given: findUnique returns a version whose tags is None (defensive — production path
                // always populates Some(list), but the domain model default is none())
                givenDeletableRow(temurinRow, tags = none())

                // when
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: succeeds — getOrElse { emptyList() } collapses None to no conflict
                result.shouldBeRight()
            }

            should("return VersionNotFound when version does not exist") {
                // given: version is not found in repository
                coEvery { versionsRepo.findUnique(any(), any(), any(), any()) } returns Either.Right(none())

                // when: trying to delete a non-existent version
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: returns VersionNotFound
                result shouldBeLeft DomainError.VersionNotFound(JAVA_CANDIDATE, "25.0.4.1+1")
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
            }

            should("return TagConflict when version has active tags") {
                // given: findUnique returns a version whose tags are already populated (mirrors
                // production — PostgresVersionRepository.findUnique applies withBatchFetchedTags())
                givenStoredRow(temurinRow, tags = listOf("lts", "latest").some())

                // when: trying to delete a version with active tags
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: returns TagConflict with the tag names — taken directly from the fetched
                // version, no extra DB lookup required
                result shouldBeLeft DomainError.TagConflict(listOf("lts", "latest"))
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
                coVerify(exactly = 0) { tagService.findTagNamesByVersionId(any()) }
            }

            should("return DatabaseError when findUnique fails") {
                // given: findUnique fails with a database error
                val dbFailure = DatabaseFailure.QueryExecutionFailure("connection lost", RuntimeException("timeout"))
                coEvery { versionsRepo.findUnique(any(), any(), any(), any()) } returns Either.Left(dbFailure)

                // when: deleting a version when the lookup fails
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: returns DatabaseError
                result shouldBeLeft DomainError.DatabaseError(dbFailure)
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
            }

            should("return VersionNotFound when delete returns zero rows") {
                // given: delete affected zero rows (race condition)
                givenStoredRow(temurinRow)
                coEvery { versionsRepo.delete(temurinRow) } returns Either.Right(0)

                // when: deleting a version that disappears between check and delete
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: returns VersionNotFound
                result shouldBeLeft DomainError.VersionNotFound(JAVA_CANDIDATE, "25.0.4.1+1")
            }

            should("take the java write lock of the row's scope before reading the row") {
                // given: an untagged TEMURIN java row
                givenDeletableRow(temurinRow)

                // when: deleting it
                service.delete(temurinRow, NIL_UUID, "admin")

                // then: the java scope is locked before the row is read
                coVerifyOrder {
                    versionsRepo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                    versionsRepo.findUnique(JAVA_CANDIDATE, "25.0.4.1+1", Platform.LINUX_X64, Distribution.TEMURIN.some())
                }
            }

            should("never take the java write lock to delete a scala row") {
                // given: an untagged scala row
                val scalaRow =
                    UniqueVersion(
                        candidate = "scala",
                        version = "3.7.3",
                        distribution = none(),
                        platform = Platform.UNIVERSAL,
                    )
                givenDeletableRow(scalaRow)

                // when: deleting it
                service.delete(scalaRow, NIL_UUID, "admin")

                // then: no java scope is locked
                coVerify(exactly = 0) { versionsRepo.lockJavaWrites(any(), any()) }
            }

            should("answer DatabaseError and read nothing when the java write lock fails") {
                // given: taking the java write lock fails
                val lockFailure = DatabaseFailure.QueryExecutionFailure("lock failed", RuntimeException("timeout"))
                coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Left(lockFailure)

                // when: deleting a java row
                val result = service.delete(temurinRow, NIL_UUID, "admin")

                // then: the failure surfaces before the row is read
                result shouldBeLeft DomainError.DatabaseError(lockFailure)
                coVerify(exactly = 0) { versionsRepo.findUnique(any(), any(), any(), any()) }
            }

            should("make every repository call inside the transaction") {
                // given: a transaction that never runs its block and answers a database failure
                val txFailure = DomainError.DatabaseError(DatabaseFailure.QueryExecutionFailure("tx failed", RuntimeException("x")))
                val transactional = mockk<Transactional>()
                coEvery { transactional.inTransaction<DomainError, Version>(any()) } returns Either.Left(txFailure)
                val txService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional)

                // when: deleting a java row
                val result = txService.delete(temurinRow, NIL_UUID, "admin")

                // then: the failure surfaces and no repository was touched outside the block
                result shouldBeLeft txFailure
                coVerify { versionsRepo wasNot Called }
            }

            should("write the DELETE audit after the transaction ends") {
                // given: a transaction that checks, as its block returns, that no audit was written yet
                givenDeletableRow(temurinRow)
                val transactional = mockk<Transactional>()
                coEvery { transactional.inTransaction<DomainError, Version>(any()) } coAnswers {
                    firstArg<suspend () -> Either<DomainError, Version>>()().also {
                        coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
                    }
                }
                val txService = VersionServiceImpl(versionsRepo, tagService, auditRepo, transactional)

                // when: deleting a java row
                txService.delete(temurinRow, NIL_UUID, "admin")

                // then: the DELETE audit is written once the transaction is over
                coVerify(exactly = 1) { auditRepo.recordAudit(NIL_UUID, "admin", AuditOperation.DELETE, any()) }
            }
        }

        context("resolveByTag") {

            should("return the version when tag resolves successfully") {
                // given: repository returns the tagged version
                val version =
                    Version(
                        candidate = "java",
                        version = "25.0.2",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-25.tar.gz",
                    )
                coEvery {
                    versionsRepo.findByTag("java", "lts", Platform.LINUX_X64, none())
                } returns Either.Right(version.some())

                // when: resolving a tag
                val result = service.resolveByTag("java", "lts", Platform.LINUX_X64, none())

                // then: returns the version
                result shouldBe Either.Right(version.some())
            }

            should("return none() when tag does not exist") {
                // given: repository returns none()
                coEvery {
                    versionsRepo.findByTag("java", "lts", Platform.LINUX_X64, none())
                } returns Either.Right(none())

                // when: resolving a non-existent tag
                val result = service.resolveByTag("java", "lts", Platform.LINUX_X64, none())

                // then: returns none()
                result shouldBe Either.Right(none())
            }

            should("return DatabaseError when repository fails") {
                // given: repository returns a database failure
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection lost",
                        RuntimeException("timeout"),
                    )
                coEvery {
                    versionsRepo.findByTag("java", "lts", Platform.LINUX_X64, none())
                } returns Either.Left(dbFailure)

                // when: resolving a tag with DB failure
                val result = service.resolveByTag("java", "lts", Platform.LINUX_X64, none())

                // then: returns DatabaseError
                result.shouldBeLeft()
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
            }
        }
    })
