package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService

class VersionServiceUnitSpec :
    ShouldSpec({
        val versionsRepo = mockk<VersionRepository>()
        val tagService = mockk<TagService>()
        val auditRepo = mockk<AuditRepository>()
        val service = VersionServiceImpl(versionsRepo, tagService, auditRepo)

        beforeEach { clearAllMocks() }

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
                val result = service.findByCandidate("java", None, None, None)

                // then: returns the list from the repository
                result shouldBe Either.Right(versions)
            }

            should("pass filter parameters to repository") {
                // given: repository returns filtered results
                coEvery {
                    versionsRepo.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        None,
                        true.some(),
                    )
                } returns Either.Right(emptyList())

                // when: finding versions with filters
                val result =
                    service.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        None,
                        true.some(),
                    )

                // then: returns empty list and passes filters correctly
                result shouldBe Either.Right(emptyList())
                coVerify {
                    versionsRepo.findByCandidate(
                        "java",
                        Platform.LINUX_X64.some(),
                        None,
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
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())

                // when: looking up a specific version
                val result =
                    service.findUnique("java", "17.0.1", Platform.LINUX_X64, None)

                // then: returns the version
                result shouldBe Either.Right(version.some())
            }

            should("return None when version not found") {
                // given: repository does not find the version
                coEvery {
                    versionsRepo.findUnique("java", "99.0.0", Platform.LINUX_X64, None)
                } returns Either.Right(None)

                // when: looking up a non-existent version
                val result =
                    service.findUnique("java", "99.0.0", Platform.LINUX_X64, None)

                // then: returns None
                result shouldBe Either.Right(None)
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
                    auditRepo.recordAudit("admin", AuditOperation.CREATE, version)
                } returns Either.Right(Unit)
                coEvery {
                    tagService.replaceTags(42, "java", None, Platform.LINUX_X64, listOf("lts", "latest"))
                } returns Either.Right(Unit)

                // when: creating a version
                val result = service.createOrUpdate(version, "admin")

                // then: succeeds and calls all three operations
                result.isRight() shouldBe true
                coVerify { versionsRepo.createOrUpdate(version) }
                coVerify { auditRepo.recordAudit("admin", AuditOperation.CREATE, version) }
                coVerify {
                    tagService.replaceTags(42, "java", None, Platform.LINUX_X64, listOf("lts", "latest"))
                }
            }

            should("create version without tags when tags are None") {
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
                    auditRepo.recordAudit("admin", AuditOperation.CREATE, version)
                } returns Either.Right(Unit)

                // when: creating a version without tags
                val result = service.createOrUpdate(version, "admin")

                // then: succeeds and does not call replaceTags
                result.isRight() shouldBe true
                coVerify { versionsRepo.createOrUpdate(version) }
                coVerify { auditRepo.recordAudit("admin", AuditOperation.CREATE, version) }
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
                val result = service.createOrUpdate(version, "admin")

                // then: returns a DatabaseError wrapping the failure
                result.isLeft() shouldBe true
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
                    auditRepo.recordAudit("admin", AuditOperation.CREATE, version)
                } returns
                    Either.Left(
                        DatabaseFailure.QueryExecutionFailure(
                            "audit table full",
                            RuntimeException("disk full"),
                        ),
                    )

                // when: creating a version with failing audit
                val result = service.createOrUpdate(version, "admin")

                // then: still succeeds (audit failure is logged but non-fatal)
                result.isRight() shouldBe true
            }

            should("still succeed when tag processing fails") {
                // given: tag processing fails but create succeeds
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                        tags = listOf("lts").some(),
                    )
                coEvery { versionsRepo.createOrUpdate(version) } returns Either.Right(42)
                coEvery {
                    auditRepo.recordAudit("admin", AuditOperation.CREATE, version)
                } returns Either.Right(Unit)
                coEvery {
                    tagService.replaceTags(42, "java", None, Platform.LINUX_X64, listOf("lts"))
                } returns
                    Either.Left(
                        DomainError.DatabaseError(
                            DatabaseFailure.QueryExecutionFailure(
                                "tag error",
                                RuntimeException("tag failure"),
                            ),
                        ),
                    )

                // when: creating a version with failing tag processing
                val result = service.createOrUpdate(version, "admin")

                // then: still succeeds (tag failure is logged but non-fatal)
                result.isRight() shouldBe true
            }
        }

        context("delete") {

            should("delete version when it exists and has no tags") {
                // given: version exists and has no tags
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "17.0.1",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery { tagService.findTagNamesByVersionId(42) } returns Either.Right(emptyList())
                coEvery {
                    auditRepo.recordAudit("admin", AuditOperation.DELETE, version)
                } returns Either.Right(Unit)
                coEvery { versionsRepo.delete(uniqueVersion) } returns Either.Right(1)

                // when: deleting the version
                val result = service.delete(uniqueVersion, "admin")

                // then: succeeds
                result.isRight() shouldBe true
                coVerify(ordering = io.mockk.Ordering.ORDERED) {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                    versionsRepo.findVersionId(uniqueVersion)
                    tagService.findTagNamesByVersionId(42)
                    auditRepo.recordAudit("admin", AuditOperation.DELETE, version)
                    versionsRepo.delete(uniqueVersion)
                }
            }

            should("return VersionNotFound when version does not exist") {
                // given: version is not found in repository
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "99.0.0",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                coEvery {
                    versionsRepo.findUnique("java", "99.0.0", Platform.LINUX_X64, None)
                } returns Either.Right(None)

                // when: trying to delete a non-existent version
                val result = service.delete(uniqueVersion, "admin")

                // then: returns VersionNotFound
                result.isLeft() shouldBe true
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.VersionNotFound>()
                    error.candidate shouldBe "java"
                    error.version shouldBe "99.0.0"
                }
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
            }

            should("return VersionNotFound when version ID is not found") {
                // given: version exists but ID lookup returns None
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "17.0.1",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(None)

                // when: trying to delete with missing version ID
                val result = service.delete(uniqueVersion, "admin")

                // then: returns VersionNotFound
                result.isLeft() shouldBe true
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.VersionNotFound>()
                }
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
            }

            should("return TagConflict when version has active tags") {
                // given: version has active tags
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "17.0.1",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery {
                    tagService.findTagNamesByVersionId(42)
                } returns Either.Right(listOf("lts", "latest"))

                // when: trying to delete a version with active tags
                val result = service.delete(uniqueVersion, "admin")

                // then: returns TagConflict with the tag names
                result.isLeft() shouldBe true
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.TagConflict>()
                    error.tags shouldBe listOf("lts", "latest")
                }
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any()) }
            }

            should("return DatabaseError when tag lookup fails") {
                // given: tag lookup fails with a database error
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "17.0.1",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                val dbFailure =
                    DatabaseFailure.QueryExecutionFailure(
                        "connection lost",
                        RuntimeException("timeout"),
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery {
                    tagService.findTagNamesByVersionId(42)
                } returns Either.Left(DomainError.DatabaseError(dbFailure))

                // when: deleting a version when tag lookup fails
                val result = service.delete(uniqueVersion, "admin")

                // then: returns DatabaseError
                result.isLeft() shouldBe true
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.DatabaseError>()
                    error.failure shouldBe dbFailure
                }
                coVerify(exactly = 0) { versionsRepo.delete(any()) }
            }

            should("return VersionNotFound when delete returns zero rows") {
                // given: delete affected zero rows (race condition)
                val uniqueVersion =
                    UniqueVersion(
                        candidate = "java",
                        version = "17.0.1",
                        distribution = None,
                        platform = Platform.LINUX_X64,
                    )
                val version =
                    Version(
                        candidate = "java",
                        version = "17.0.1",
                        platform = Platform.LINUX_X64,
                        url = "https://example.com/java-17.tar.gz",
                    )
                coEvery {
                    versionsRepo.findUnique("java", "17.0.1", Platform.LINUX_X64, None)
                } returns Either.Right(version.some())
                coEvery { versionsRepo.findVersionId(uniqueVersion) } returns Either.Right(42.some())
                coEvery { tagService.findTagNamesByVersionId(42) } returns Either.Right(emptyList())
                coEvery {
                    auditRepo.recordAudit("admin", AuditOperation.DELETE, version)
                } returns Either.Right(Unit)
                coEvery { versionsRepo.delete(uniqueVersion) } returns Either.Right(0)

                // when: deleting a version that disappears between check and delete
                val result = service.delete(uniqueVersion, "admin")

                // then: returns VersionNotFound
                result.isLeft() shouldBe true
                result.onLeft { error ->
                    error.shouldBeInstanceOf<DomainError.VersionNotFound>()
                }
            }
        }
    })
