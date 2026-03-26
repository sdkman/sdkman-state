package io.sdkman.state.adapter.secondary.persistence

import arrow.core.None
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.selectAuditRecords
import io.sdkman.state.support.selectAuditRecordsByEmail
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.withCleanDatabase
import java.util.UUID

private val NIL_UUID: UUID = UUID(0L, 0L)
private val VENDOR_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

@Tags("integration")
class PostgresAuditRepositoryIntegrationSpec :
    ShouldSpec({

        val repo = PostgresAuditRepository()

        context("recordAudit") {
            should("record a CREATE audit for a Version") {
                withCleanDatabase {
                    // given: a version to audit
                    val version =
                        Version(
                            candidate = "java",
                            version = "21.0.1",
                            platform = Platform.LINUX_X64,
                            url = "https://java-21.0.1",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                            md5sum = "abc123def456".some(),
                        )

                    // when: recording a CREATE audit
                    val result = repo.recordAudit(NIL_UUID, "test-admin", AuditOperation.CREATE, version)

                    // then: audit record is persisted
                    result.shouldBeRight()
                    val records = selectAuditRecords()
                    records shouldHaveSize 1
                    records.first().vendorId shouldBe NIL_UUID
                    records.first().email shouldBe "test-admin"
                    records.first().operation shouldBe AuditOperation.CREATE
                    records.first().versionData shouldContain "java"
                    records.first().versionData shouldContain "21.0.1"
                    records.first().versionData shouldContain "LINUX_X64"
                }
            }

            should("record a DELETE audit for a UniqueTag") {
                withCleanDatabase {
                    // given: a tag to audit
                    val uniqueTag =
                        UniqueTag(
                            candidate = "java",
                            tag = "latest",
                            distribution = Distribution.TEMURIN.some(),
                            platform = Platform.LINUX_X64,
                        )

                    // when: recording a DELETE audit
                    val result = repo.recordAudit(VENDOR_UUID, "tag-admin", AuditOperation.DELETE, uniqueTag)

                    // then: audit record is persisted
                    result.shouldBeRight()
                    val records = selectAuditRecords()
                    records shouldHaveSize 1
                    records.first().vendorId shouldBe VENDOR_UUID
                    records.first().email shouldBe "tag-admin"
                    records.first().operation shouldBe AuditOperation.DELETE
                    records.first().versionData shouldContain "java"
                    records.first().versionData shouldContain "latest"
                }
            }

            should("record multiple audit entries with different emails") {
                withCleanDatabase {
                    // given: two versions
                    val version1 =
                        Version(
                            candidate = "java",
                            version = "17.0.1",
                            platform = Platform.LINUX_X64,
                            url = "https://java-17.0.1",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val version2 =
                        Version(
                            candidate = "kotlin",
                            version = "1.9.0",
                            platform = Platform.UNIVERSAL,
                            url = "https://kotlin-1.9.0",
                            visible = true.some(),
                            distribution = Distribution.JETBRAINS.some(),
                        )

                    // when: recording audits from different users
                    repo.recordAudit(NIL_UUID, "admin-1@example.com", AuditOperation.CREATE, version1)
                    repo.recordAudit(VENDOR_UUID, "admin-2@example.com", AuditOperation.CREATE, version2)

                    // then: both records exist and are filterable by email
                    val allRecords = selectAuditRecords()
                    allRecords shouldHaveSize 2

                    val admin1Records = selectAuditRecordsByEmail("admin-1@example.com")
                    admin1Records shouldHaveSize 1
                    admin1Records.first().versionData shouldContain "java"

                    val admin2Records = selectAuditRecordsByEmail("admin-2@example.com")
                    admin2Records shouldHaveSize 1
                    admin2Records.first().versionData shouldContain "kotlin"
                }
            }

            should("record audit entries filterable by operation") {
                withCleanDatabase {
                    // given: a version and a tag
                    val version =
                        Version(
                            candidate = "java",
                            version = "21.0.1",
                            platform = Platform.LINUX_X64,
                            url = "https://java-21.0.1",
                            visible = true.some(),
                            distribution = Distribution.TEMURIN.some(),
                        )
                    val uniqueTag =
                        UniqueTag(
                            candidate = "java",
                            tag = "latest",
                            distribution = Distribution.TEMURIN.some(),
                            platform = Platform.LINUX_X64,
                        )

                    // when: recording CREATE and DELETE audits
                    repo.recordAudit(NIL_UUID, "admin@example.com", AuditOperation.CREATE, version)
                    repo.recordAudit(NIL_UUID, "admin@example.com", AuditOperation.DELETE, uniqueTag)

                    // then: records are filterable by operation type
                    val createRecords = selectAuditRecordsByOperation(AuditOperation.CREATE)
                    createRecords shouldHaveSize 1
                    createRecords.first().versionData shouldContain "21.0.1"

                    val deleteRecords = selectAuditRecordsByOperation(AuditOperation.DELETE)
                    deleteRecords shouldHaveSize 1
                    deleteRecords.first().versionData shouldContain "latest"
                }
            }

            should("serialize Version without distribution correctly") {
                withCleanDatabase {
                    // given: a version without distribution (e.g., Gradle)
                    val version =
                        Version(
                            candidate = "gradle",
                            version = "8.12",
                            platform = Platform.UNIVERSAL,
                            url = "https://gradle-8.12",
                            visible = true.some(),
                            distribution = None,
                        )

                    // when: recording audit
                    val result = repo.recordAudit(NIL_UUID, "admin@example.com", AuditOperation.CREATE, version)

                    // then: audit record stores version data without distribution
                    result.shouldBeRight()
                    val records = selectAuditRecords()
                    records shouldHaveSize 1
                    records.first().versionData shouldContain "gradle"
                    records.first().versionData shouldContain "UNIVERSAL"
                }
            }
        }
    })
