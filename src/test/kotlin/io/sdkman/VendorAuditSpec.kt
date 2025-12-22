package io.sdkman

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders.Authorization
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.*
import kotlin.time.Duration.Companion.seconds

private const val BasicAuthHeader = "Basic dGVzdHVzZXI6cGFzc3dvcmQxMjM="

class VendorAuditSpec : ShouldSpec({

    should("create audit record when POST creates a new version") {
        // given: a valid version request
        val version = Version(
            candidate = "java",
            version = "17.0.1",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.1.tar.gz",
            visible = true.some(),
            distribution = Distribution.TEMURIN.some(),
            sha256sum = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1".some()
        )
        val requestBody = version.toJsonString()

        withCleanDatabase {
            // when: POST /versions is called
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }

            // then: audit record is created with correct data
            val auditRecords = selectAuditRecords()
            auditRecords shouldHaveSize 1
            val auditRecord = auditRecords.first()
            auditRecord.username shouldBe "testuser"
            auditRecord.operation shouldBe AuditOperation.POST

            val deserializedVersion = deserializeVersionData(auditRecord.versionData)
            deserializedVersion shouldBe version
        }
    }

    should("create audit record when DELETE removes a version") {
        // given: an existing version
        val version = Version(
            candidate = "kotlin",
            version = "1.9.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/kotlin-1.9.0.zip",
            visible = true.some(),
            distribution = None
        )
        insertVersions(version)

        val deleteRequest = """
            {
                "candidate": "kotlin",
                "version": "1.9.0",
                "platform": "UNIVERSAL",
                "distribution": null
            }
        """.trimIndent()

        withCleanDatabase {
            insertVersions(version)

            // when: DELETE /versions is called
            withTestApplication {
                val response = client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(deleteRequest)
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }

            // then: audit record is created with operation=DELETE
            val auditRecords = selectAuditRecordsByOperation(AuditOperation.DELETE)
            auditRecords shouldHaveSize 1
            val auditRecord = auditRecords.first()
            auditRecord.username shouldBe "testuser"
            auditRecord.operation shouldBe AuditOperation.DELETE

            val deserializedVersion = deserializeVersionData(auditRecord.versionData)
            deserializedVersion shouldBe version
        }
    }

    should("capture correct username from authentication") {
        // given: a version request with authenticated user
        val version = Version(
            candidate = "gradle",
            version = "8.0.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/gradle-8.0.0.zip",
            visible = true.some(),
            distribution = None
        )

        withCleanDatabase {
            withTestApplication {
                client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(version.toJsonString())
                    header(Authorization, BasicAuthHeader)
                }
            }

            // then: audit record has correct username
            val auditRecords = selectAuditRecordsByUsername("testuser")
            auditRecords shouldHaveSize 1
            auditRecords.first().username shouldBe "testuser"
        }
    }

    should("capture accurate timestamp within reasonable delta") {
        // given: a version request
        val version = Version(
            candidate = "maven",
            version = "3.9.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/maven-3.9.0.zip",
            visible = true.some(),
            distribution = None
        )

        withCleanDatabase {
            val beforeTime = kotlinx.datetime.Clock.System.now()

            withTestApplication {
                client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(version.toJsonString())
                    header(Authorization, BasicAuthHeader)
                }
            }

            val afterTime = kotlinx.datetime.Clock.System.now()

            // then: timestamp is within reasonable range (5 seconds)
            val auditRecords = selectAuditRecords()
            auditRecords shouldHaveSize 1
            val timestamp = auditRecords.first().timestamp

            (timestamp >= beforeTime - 5.seconds) shouldBe true
            (timestamp <= afterTime + 5.seconds) shouldBe true
        }
    }

    should("serialize version data with all optional fields populated") {
        // given: version with all optional fields
        val version = Version(
            candidate = "java",
            version = "21.0.0",
            platform = Platform.LINUX_ARM64,
            url = "https://example.com/java-21.0.0.tar.gz",
            visible = false.some(),
            distribution = Distribution.ZULU.some(),
            md5sum = "abc123def456abc123def456abc123de".some(),
            sha256sum = "abc123def456abc123def456abc123def456abc123def456abc123def456abc1".some(),
            sha512sum = "abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123def456abc123de".some()
        )

        withCleanDatabase {
            withTestApplication {
                val response = client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(version.toJsonString())
                    header(Authorization, BasicAuthHeader)
                }
                response.status shouldBe HttpStatusCode.NoContent
            }

            // then: all fields are correctly serialized
            val auditRecords = selectAuditRecords()
            auditRecords shouldHaveSize 1
            val deserializedVersion = deserializeVersionData(auditRecords.first().versionData)

            deserializedVersion.candidate shouldBe version.candidate
            deserializedVersion.version shouldBe version.version
            deserializedVersion.platform shouldBe version.platform
            deserializedVersion.url shouldBe version.url
            deserializedVersion.visible shouldBe version.visible
            deserializedVersion.distribution shouldBe version.distribution
            deserializedVersion.md5sum shouldBe version.md5sum
            deserializedVersion.sha256sum shouldBe version.sha256sum
            deserializedVersion.sha512sum shouldBe version.sha512sum
        }
    }

    should("serialize version data with minimal required fields only") {
        // given: version with only required fields
        val version = Version(
            candidate = "scala",
            version = "3.3.0",
            platform = Platform.UNIVERSAL,
            url = "https://example.com/scala-3.3.0.zip",
            visible = None,
            distribution = None,
            md5sum = None,
            sha256sum = None,
            sha512sum = None
        )

        withCleanDatabase {
            withTestApplication {
                client.post("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody(version.toJsonString())
                    header(Authorization, BasicAuthHeader)
                }
            }

            // then: optional fields are correctly serialized as None
            val auditRecords = selectAuditRecords()
            val deserializedVersion = deserializeVersionData(auditRecords.first().versionData)

            deserializedVersion.candidate shouldBe version.candidate
            deserializedVersion.distribution shouldBe None
            deserializedVersion.md5sum shouldBe None
            deserializedVersion.sha256sum shouldBe None
            deserializedVersion.sha512sum shouldBe None
        }
    }

    should("handle DELETE with distribution=Some vs distribution=None") {
        // given: two versions - one with distribution, one without
        val versionWithDist = Version(
            candidate = "java",
            version = "17.0.0",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.0-temurin.tar.gz",
            visible = true.some(),
            distribution = Distribution.TEMURIN.some()
        )
        val versionNoDist = Version(
            candidate = "java",
            version = "17.0.0",
            platform = Platform.LINUX_X64,
            url = "https://example.com/java-17.0.0.tar.gz",
            visible = true.some(),
            distribution = None
        )

        withCleanDatabase {
            insertVersions(versionWithDist, versionNoDist)

            // when: deleting version with distribution
            withTestApplication {
                client.delete("/versions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"candidate":"java","version":"17.0.0","platform":"LINUX_X64","distribution":"TEMURIN"}""")
                    header(Authorization, BasicAuthHeader)
                }
            }

            // then: audit record captures version with distribution
            val auditRecords = selectAuditRecordsByOperation(AuditOperation.DELETE)
            auditRecords shouldHaveSize 1
            val deserializedVersion = deserializeVersionData(auditRecords.first().versionData)
            deserializedVersion.distribution shouldBe Distribution.TEMURIN.some()
        }
    }
})
