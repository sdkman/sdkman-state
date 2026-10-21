package io.sdkman.state.application.service

import arrow.core.Either
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.sdkman.state.application.validation.InvalidVersionFormatError
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.JavaRefusal
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.repository.VersionRepository
import io.sdkman.state.domain.service.TagService
import io.sdkman.state.support.passthroughTransactional
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.transactionalFailingWithoutRunning
import java.util.UUID

private val VENDOR_UUID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private const val VENDOR_EMAIL = "disco@sdkman.io"
private val ADMIN_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private const val ADMIN_EMAIL = "admin@sdkman.io"
private const val VERSION_ID = 42

class JavaVersionServiceUnitSpec :
    ShouldSpec({
        val versionsRepo = mockk<VersionRepository>()
        val tagService = mockk<TagService>()
        val auditRepo = mockk<AuditRepository>()
        val service = JavaVersionServiceImpl(versionsRepo, tagService, auditRepo, passthroughTransactional())

        fun post(
            version: String,
            distribution: Distribution = Distribution.TEMURIN,
        ): Version =
            Version(
                candidate = JAVA_CANDIDATE,
                version = version,
                platform = Platform.LINUX_X64,
                url = "https://example.com/jdk-$version.tar.gz",
                distribution = distribution.some(),
            )

        fun visibleRow(
            version: String,
            vararg tags: String,
            distribution: Distribution = Distribution.TEMURIN,
        ): Version = post(version, distribution).copy(visible = true.some(), tags = tags.toList().some())

        fun givenScopeRows(vararg rows: Version) {
            coEvery { versionsRepo.findJavaRows(any(), Platform.LINUX_X64) } returns Either.Right(rows.toList())
        }

        val databaseFailure = DatabaseFailure.QueryExecutionFailure("write failed", RuntimeException("deadlock"))

        fun serviceWithUnrunTransaction(): JavaVersionServiceImpl =
            JavaVersionServiceImpl(
                versionsRepo,
                tagService,
                auditRepo,
                transactionalFailingWithoutRunning(DomainError.DatabaseError(databaseFailure)),
            )

        beforeEach {
            clearAllMocks()
            coEvery { versionsRepo.lockJavaWrites(any(), any()) } returns Either.Right(Unit)
            coEvery { versionsRepo.createOrUpdate(any()) } returns Either.Right(VERSION_ID)
            coEvery { versionsRepo.hideJavaVersions(any(), any(), any()) } returns Either.Right(0)
            coEvery { tagService.assignTagToVersion(any(), any(), any(), any(), any()) } returns Either.Right(Unit)
            coEvery { tagService.replaceTags(any(), any(), any(), any(), any()) } returns Either.Right(Unit)
            coEvery { tagService.findTagNamesByVersionId(VERSION_ID) } returns Either.Right(emptyList())
            coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Right(Unit)
        }

        context("publish") {

            should("take the java write lock of the post's scope before reading its rows") {
                // given: TEMURIN 25.0.4.1+1 is the head of its series
                givenScopeRows(visibleRow("25.0.4.1+1"))

                // when: the vendor posts TEMURIN 25.0.5.0+3
                service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the scope is locked before it is read
                coVerifyOrder {
                    versionsRepo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                    versionsRepo.findJavaRows(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                }
            }

            should("make every repository call inside the transaction") {
                // given: a transaction that never runs its block and answers a database failure
                val txService = serviceWithUnrunTransaction()

                // when: the vendor posts TEMURIN 25.0.5.0+3
                val result = txService.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the failure surfaces and no repository was touched outside the block
                result shouldBeLeft DomainError.DatabaseError(databaseFailure)
                coVerify { versionsRepo wasNot Called }
                coVerify { tagService wasNot Called }
            }

            should("store a post that beats the head as visible") {
                // given: TEMURIN 25.0.4.1+1 is the head, tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the vendor posts the newer TEMURIN 25.0.5.0+3
                val result = service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the post is stored visible
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(post("25.0.5.0+3").copy(visible = true.some())) }
            }

            should("hide the other visible rows of the series a new head joins") {
                // given: two visible rows of the 25 feature line and one of the 21 feature line
                givenScopeRows(visibleRow("25.0.4.1+1"), visibleRow("25.0.3.0"), visibleRow("21.0.12+1.1"))

                // when: the vendor posts TEMURIN 25.0.5.0+3
                service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: only the rows of the 25 feature line are hidden
                coVerify {
                    versionsRepo.hideJavaVersions(
                        Distribution.TEMURIN.some(),
                        Platform.LINUX_X64,
                        listOf("25.0.4.1+1", "25.0.3.0"),
                    )
                }
            }

            should("move the feature, latest and lts tags onto a new head") {
                // given: TEMURIN 25.0.4.1+1 is the head, tagged "lts" and "25", and no row holds "latest"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the vendor posts TEMURIN 25.0.5.0+3
                service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: each tag is assigned to the stored post within the scope
                listOf("25", "latest", "lts").forEach { tag ->
                    coVerify {
                        tagService.assignTagToVersion(VERSION_ID, JAVA_CANDIDATE, Distribution.TEMURIN.some(), Platform.LINUX_X64, tag)
                    }
                }
            }

            should("audit a new head with the visibility and tags it was stored with") {
                // given: the stored post ends up holding "25", "latest" and "lts"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))
                coEvery { tagService.findTagNamesByVersionId(VERSION_ID) } returns Either.Right(listOf("25", "latest", "lts"))

                // when: the vendor posts TEMURIN 25.0.5.0+3
                service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the CREATE entry records the stored visibility and tags
                val stored = post("25.0.5.0+3").copy(visible = true.some(), tags = listOf("25", "latest", "lts").some())
                coVerify { auditRepo.recordAudit(VENDOR_UUID, VENDOR_EMAIL, AuditOperation.CREATE, stored) }
            }

            should("audit each hidden row as retired, keeping only the tags it did not give up") {
                // given: the head TEMURIN 25.0.4.1+1 holds "lts", "25" and the admin's "ga"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25", "ga"))

                // when: the vendor posts TEMURIN 25.0.5.0+3
                service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the RETIRE entry records the row hidden and holding "ga" only
                val retired = visibleRow("25.0.4.1+1", "ga").copy(visible = false.some())
                coVerify { auditRepo.recordAudit(VENDOR_UUID, VENDOR_EMAIL, AuditOperation.RETIRE, retired) }
            }

            should("store a post below the head as hidden") {
                // given: TEMURIN 25.0.4.1+1 is the head, tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the vendor posts the older TEMURIN 25.0.4.0+9
                val result = service.publish(post("25.0.4.0+9"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the post is stored hidden
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(post("25.0.4.0+9").copy(visible = false.some())) }
            }

            should("hide no row and move no tag for a post below the head") {
                // given: TEMURIN 25.0.4.1+1 is the head, tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the vendor posts the older TEMURIN 25.0.4.0+9
                service.publish(post("25.0.4.0+9"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the head keeps its visibility and its tags
                coVerify { versionsRepo.hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, emptyList()) }
                coVerify(exactly = 0) { tagService.assignTagToVersion(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { tagService.replaceTags(any(), any(), any(), any(), any()) }
            }

            should("refuse a post that cannot be ordered against the head, naming the head") {
                // given: KONA 21.0.9.0+r17 is the head
                givenScopeRows(visibleRow("21.0.9.0+r17", distribution = Distribution.KONA))

                // when: the vendor posts KONA 21.0.9.0+1
                val result = service.publish(post("21.0.9.0+1", Distribution.KONA), VENDOR_UUID, VENDOR_EMAIL)

                // then: the post is refused and the error names the head
                result shouldBeLeft DomainError.JavaPostRefused(version = "21.0.9.0+1", head = "21.0.9.0+r17")
            }

            should("write no row, hide no row and move no tag for a refused post") {
                // given: KONA 21.0.9.0+r17 is the head
                givenScopeRows(visibleRow("21.0.9.0+r17", distribution = Distribution.KONA))

                // when: the vendor posts KONA 21.0.9.0+1
                service.publish(post("21.0.9.0+1", Distribution.KONA), VENDOR_UUID, VENDOR_EMAIL)

                // then: nothing is written
                coVerify(exactly = 0) { versionsRepo.createOrUpdate(any()) }
                coVerify(exactly = 0) { versionsRepo.hideJavaVersions(any(), any(), any()) }
                coVerify(exactly = 0) { tagService.assignTagToVersion(any(), any(), any(), any(), any()) }
            }

            should("audit a refused post with a REFUSE entry naming the head, and nothing else") {
                // given: KONA 21.0.9.0+r17 is the head
                givenScopeRows(visibleRow("21.0.9.0+r17", distribution = Distribution.KONA))

                // when: the vendor posts KONA 21.0.9.0+1
                service.publish(post("21.0.9.0+1", Distribution.KONA), VENDOR_UUID, VENDOR_EMAIL)

                // then: the only audit entry is the refusal, attributed to the vendor
                val refusal = JavaRefusal(post("21.0.9.0+1", Distribution.KONA), head = "21.0.9.0+r17")
                coVerify(exactly = 1) { auditRepo.recordAudit(any(), any(), any(), any()) }
                coVerify { auditRepo.recordAudit(VENDOR_UUID, VENDOR_EMAIL, AuditOperation.REFUSE, refusal) }
            }

            should("return DatabaseError and audit nothing when hiding fails") {
                // given: TEMURIN 25.0.4.1+1 is the head and hiding it fails
                givenScopeRows(visibleRow("25.0.4.1+1"))
                coEvery { versionsRepo.hideJavaVersions(any(), any(), any()) } returns Either.Left(databaseFailure)

                // when: the vendor posts TEMURIN 25.0.5.0+3
                val result = service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the failure surfaces and no audit entry is written
                result shouldBeLeft DomainError.DatabaseError(databaseFailure)
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("still succeed when audit logging fails") {
                // given: TEMURIN 25.0.4.1+1 is the head and every audit write fails
                givenScopeRows(visibleRow("25.0.4.1+1"))
                coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Left(databaseFailure)

                // when: the vendor posts TEMURIN 25.0.5.0+3
                val result = service.publish(post("25.0.5.0+3"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the post still succeeds
                result.shouldBeRight()
            }

            should("answer ValidationFailed for a version outside the grammar") {
                // when: the vendor posts the three-component TEMURIN 25.0.5
                val result = service.publish(post("25.0.5"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the error describes the grammar
                result shouldBeLeft DomainError.ValidationFailed(InvalidVersionFormatError(version = "25.0.5").message)
            }

            should("take no lock for a version outside the grammar") {
                // when: the vendor posts the three-component TEMURIN 25.0.5
                service.publish(post("25.0.5"), VENDOR_UUID, VENDOR_EMAIL)

                // then: the java scope is never locked
                coVerify(exactly = 0) { versionsRepo.lockJavaWrites(any(), any()) }
            }
        }

        context("overrideAsAdmin") {

            should("take the java write lock of the post's scope before storing it") {
                // given: TEMURIN 25.0.4.1+1 is visible
                givenScopeRows(visibleRow("25.0.4.1+1"))

                // when: the admin posts TEMURIN 25.0.4.0+9
                service.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the scope is locked before the post is stored
                coVerifyOrder {
                    versionsRepo.lockJavaWrites(Distribution.TEMURIN.some(), Platform.LINUX_X64)
                    versionsRepo.createOrUpdate(any())
                }
            }

            should("make every repository call inside the transaction") {
                // given: a transaction that never runs its block and answers a database failure
                val txService = serviceWithUnrunTransaction()

                // when: the admin posts TEMURIN 25.0.4.0+9
                val result = txService.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the failure surfaces and no repository was touched outside the block
                result shouldBeLeft DomainError.DatabaseError(databaseFailure)
                coVerify { versionsRepo wasNot Called }
                coVerify { tagService wasNot Called }
            }

            should("store a post without visible as visible, whatever its order") {
                // given: TEMURIN 25.0.4.1+1 is the head
                givenScopeRows(visibleRow("25.0.4.1+1"))

                // when: the admin posts the older TEMURIN 25.0.4.0+9 without visible
                val result = service.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the post is stored visible
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(post("25.0.4.0+9").copy(visible = true.some())) }
            }

            should("store a post with visible false as hidden") {
                // when: the admin posts TEMURIN 25.0.4.1+1 with visible false
                val result = service.overrideAsAdmin(post("25.0.4.1+1").copy(visible = false.some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: the post is stored hidden
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(post("25.0.4.1+1").copy(visible = false.some())) }
            }

            should("hide every other visible row of the series when visible is true") {
                // given: the stored post, two visible and one hidden row of the 25 feature line, and a 21 row
                givenScopeRows(
                    visibleRow("25.0.4.1+1"),
                    visibleRow("25.0.4.0+9"),
                    visibleRow("25.0.3.0"),
                    visibleRow("25.0.2.0").copy(visible = false.some()),
                    visibleRow("21.0.12+1.1"),
                )

                // when: the admin posts the older TEMURIN 25.0.4.0+9 with visible true
                service.overrideAsAdmin(post("25.0.4.0+9").copy(visible = true.some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: only the other visible rows of the 25 feature line are hidden
                coVerify {
                    versionsRepo.hideJavaVersions(
                        Distribution.TEMURIN.some(),
                        Platform.LINUX_X64,
                        listOf("25.0.4.1+1", "25.0.3.0"),
                    )
                }
            }

            should("hide every other visible row of the series when visible is absent") {
                // given: TEMURIN 25.0.4.1+1 is the head
                givenScopeRows(visibleRow("25.0.4.1+1"))

                // when: the admin posts the older TEMURIN 25.0.4.0+9 without visible
                service.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the head is hidden
                coVerify { versionsRepo.hideJavaVersions(Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("25.0.4.1+1")) }
            }

            should("hide no other row when visible is false") {
                // given: TEMURIN 25.0.4.1+1 and 25.0.3.0 are visible
                givenScopeRows(visibleRow("25.0.4.1+1"), visibleRow("25.0.3.0"))

                // when: the admin posts TEMURIN 25.0.3.0 with visible false
                service.overrideAsAdmin(post("25.0.3.0").copy(visible = false.some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: no row is hidden besides the post itself
                coVerify(exactly = 0) { versionsRepo.hideJavaVersions(any(), any(), any()) }
            }

            should("store a legacy version the grammar rejects") {
                // when: the admin posts the three-component TEMURIN 25.0.3
                val result = service.overrideAsAdmin(post("25.0.3"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the post is stored as given, visible
                result.shouldBeRight()
                coVerify { versionsRepo.createOrUpdate(post("25.0.3").copy(visible = true.some())) }
            }

            should("hide nothing for a legacy version") {
                // given: TEMURIN 25.0.4.1+1 is visible
                givenScopeRows(visibleRow("25.0.4.1+1"))

                // when: the admin posts the three-component TEMURIN 25.0.3
                service.overrideAsAdmin(post("25.0.3"), ADMIN_UUID, ADMIN_EMAIL)

                // then: no row is hidden
                coVerify(exactly = 0) { versionsRepo.hideJavaVersions(any(), any(), any()) }
            }

            should("give the posted row exactly the listed tags") {
                // given: TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the admin posts TEMURIN 25.0.4.0+9 with tags "lts"
                service.overrideAsAdmin(post("25.0.4.0+9").copy(tags = listOf("lts").some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: the posted row's tags are replaced with "lts" within the scope
                coVerify {
                    tagService.replaceTags(VERSION_ID, JAVA_CANDIDATE, Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("lts"))
                }
            }

            should("strip every tag from the posted row when tags are absent") {
                // given: TEMURIN 25.0.4.1+1 is visible and tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the admin posts TEMURIN 25.0.4.1+1 without tags
                service.overrideAsAdmin(post("25.0.4.1+1"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the posted row's tags are replaced with none
                coVerify {
                    tagService.replaceTags(VERSION_ID, JAVA_CANDIDATE, Distribution.TEMURIN.some(), Platform.LINUX_X64, emptyList())
                }
            }

            should("move no tag besides the listed ones") {
                // given: TEMURIN 25.0.4.1+1 is the head, tagged "lts" and "25"
                givenScopeRows(visibleRow("25.0.4.1+1", "lts", "25"))

                // when: the admin posts the newer TEMURIN 25.0.5.0+3 without tags
                service.overrideAsAdmin(post("25.0.5.0+3"), ADMIN_UUID, ADMIN_EMAIL)

                // then: no single tag is moved onto the post
                coVerify(exactly = 0) { tagService.assignTagToVersion(any(), any(), any(), any(), any()) }
            }

            should("audit an override with the visibility and tags it was stored with") {
                // given: the stored post ends up holding "lts"
                givenScopeRows(visibleRow("25.0.4.1+1", "25"))
                coEvery { tagService.findTagNamesByVersionId(VERSION_ID) } returns Either.Right(listOf("lts"))

                // when: the admin posts TEMURIN 25.0.4.0+9 with tags "lts"
                service.overrideAsAdmin(post("25.0.4.0+9").copy(tags = listOf("lts").some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: the CREATE entry records the stored visibility and tags, attributed to the admin
                val stored = post("25.0.4.0+9").copy(visible = true.some(), tags = listOf("lts").some())
                coVerify { auditRepo.recordAudit(ADMIN_UUID, ADMIN_EMAIL, AuditOperation.CREATE, stored) }
            }

            should("audit each hidden row as retired, with the tags it kept") {
                // given: after the listed "lts" moved off it, TEMURIN 25.0.4.1+1 holds "25" only
                givenScopeRows(visibleRow("25.0.4.1+1", "25"))

                // when: the admin posts TEMURIN 25.0.4.0+9 with tags "lts"
                service.overrideAsAdmin(post("25.0.4.0+9").copy(tags = listOf("lts").some()), ADMIN_UUID, ADMIN_EMAIL)

                // then: the RETIRE entry records the row hidden and holding "25", attributed to the admin
                val retired = visibleRow("25.0.4.1+1", "25").copy(visible = false.some())
                coVerify { auditRepo.recordAudit(ADMIN_UUID, ADMIN_EMAIL, AuditOperation.RETIRE, retired) }
            }

            should("return DatabaseError and audit nothing when hiding fails") {
                // given: TEMURIN 25.0.4.1+1 is visible and hiding it fails
                givenScopeRows(visibleRow("25.0.4.1+1"))
                coEvery { versionsRepo.hideJavaVersions(any(), any(), any()) } returns Either.Left(databaseFailure)

                // when: the admin posts TEMURIN 25.0.4.0+9
                val result = service.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the failure surfaces and no audit entry is written
                result shouldBeLeft DomainError.DatabaseError(databaseFailure)
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }

            should("still succeed when audit logging fails") {
                // given: TEMURIN 25.0.4.1+1 is visible and every audit write fails
                givenScopeRows(visibleRow("25.0.4.1+1"))
                coEvery { auditRepo.recordAudit(any(), any(), any(), any()) } returns Either.Left(databaseFailure)

                // when: the admin posts TEMURIN 25.0.4.0+9
                val result = service.overrideAsAdmin(post("25.0.4.0+9"), ADMIN_UUID, ADMIN_EMAIL)

                // then: the post still succeeds
                result.shouldBeRight()
            }
        }
    })
