package io.sdkman.state.adapter.secondary.persistence

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.dropSchema
import io.sdkman.state.support.migrateSchemaTo
import io.sdkman.state.support.migrateSchemaToLatest
import io.sdkman.state.support.sharedTestDataSource
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

@Tags("integration")
class LtsRetagMigrationIntegrationSpec :
    ShouldSpec({

        beforeSpec {
            dropSchema(RETAG_SCHEMA)
            migrateSchemaTo(RETAG_SCHEMA, "20")
            RETAG_ROWS.forEach(::seedTaggedVersion)
            migrateSchemaToLatest(RETAG_SCHEMA)
        }

        afterSpec {
            dropSchema(RETAG_SCHEMA)
        }

        context("V21") {
            should("retag a non-java UNIVERSAL lts as stable") {
                tagsOf("gradle", "8.14", "UNIVERSAL") shouldContainExactly listOf("stable")
            }

            should("retag scala lts as stable at UNIVERSAL and LINUX_X64") {
                tagsOf("scala", "3.7.0", "UNIVERSAL") shouldContainExactly listOf("stable")
                tagsOf("scala", "3.7.0", "LINUX_X64") shouldContainExactly listOf("stable")
            }

            should("keep latest on a row retagged from lts") {
                tagsOf("jpx", "1.2.0", "UNIVERSAL") shouldContainExactly listOf("latest", "stable")
            }

            should("stamp last_updated_at on a retagged row") {
                lastUpdatedAt("gradle", "8.14", "UNIVERSAL").isAfter(SEEDED_AT) shouldBe true
            }

            should("skip an lts whose scope already holds stable") {
                tagsOf("groovy", "4.0.0", "UNIVERSAL") shouldContainExactly listOf("lts")
                tagsOf("groovy", "5.0.0", "UNIVERSAL") shouldContainExactly listOf("stable")
            }

            should("skip a UNIVERSAL lts when its candidate holds stable at LINUX_X64") {
                tagsOf("maven", "3.9.0", "UNIVERSAL") shouldContainExactly listOf("lts")
                tagsOf("maven", "3.9.1", "LINUX_X64") shouldContainExactly listOf("stable")
            }

            should("leave a skipped lts row untouched") {
                lastUpdatedAt("groovy", "4.0.0", "UNIVERSAL") shouldBe SEEDED_AT
            }

            should("keep java lts") {
                tagsOf("java", "21.0.9", "LINUX_X64") shouldContainExactly listOf("lts")
            }
        }
    })

private const val RETAG_SCHEMA = "lts_retag_test"

private val SEEDED_AT: LocalDateTime = LocalDateTime.of(2020, 1, 1, 0, 0)

private data class TaggedVersion(
    val candidate: String,
    val version: String,
    val platform: String,
    val tags: List<String>,
    val distribution: Option<String> = None,
)

private val RETAG_ROWS =
    listOf(
        TaggedVersion("gradle", "8.14", "UNIVERSAL", listOf("lts")),
        TaggedVersion("scala", "3.7.0", "UNIVERSAL", listOf("lts")),
        TaggedVersion("scala", "3.7.0", "LINUX_X64", listOf("lts")),
        TaggedVersion("jpx", "1.2.0", "UNIVERSAL", listOf("lts", "latest")),
        TaggedVersion("groovy", "4.0.0", "UNIVERSAL", listOf("lts")),
        TaggedVersion("groovy", "5.0.0", "UNIVERSAL", listOf("stable")),
        TaggedVersion("maven", "3.9.0", "UNIVERSAL", listOf("lts")),
        TaggedVersion("maven", "3.9.1", "LINUX_X64", listOf("stable")),
        TaggedVersion("java", "21.0.9", "LINUX_X64", listOf("lts"), distribution = Some("TEM")),
    )

private fun seedTaggedVersion(row: TaggedVersion) {
    sharedTestDataSource.connection.use { connection ->
        val versionId =
            connection
                .prepareStatement(
                    "INSERT INTO $RETAG_SCHEMA.versions (candidate, version, distribution, platform, url, visible) " +
                        "VALUES (?, ?, ?, ?, ?, true) RETURNING id",
                ).use { statement ->
                    statement.setString(1, row.candidate)
                    statement.setString(2, row.version)
                    statement.setString(3, row.distribution.getOrNull())
                    statement.setString(4, row.platform)
                    statement.setString(5, "https://${row.candidate}/${row.version}/${row.platform}")
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt("id")
                    }
                }
        connection
            .prepareStatement(
                "INSERT INTO $RETAG_SCHEMA.version_tags " +
                    "(candidate, tag, distribution, platform, version_id, last_updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                row.tags.forEach { tag ->
                    statement.setString(1, row.candidate)
                    statement.setString(2, tag)
                    statement.setString(3, row.distribution.getOrNull())
                    statement.setString(4, row.platform)
                    statement.setInt(5, versionId)
                    statement.setTimestamp(6, Timestamp.valueOf(SEEDED_AT))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }
}

private fun <T> queryTagsOf(
    candidate: String,
    version: String,
    platform: String,
    column: String,
    read: (ResultSet) -> T,
): List<T> =
    sharedTestDataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT t.$column FROM $RETAG_SCHEMA.version_tags t " +
                    "JOIN $RETAG_SCHEMA.versions v ON v.id = t.version_id " +
                    "WHERE v.candidate = ? AND v.version = ? AND v.platform = ? ORDER BY t.tag",
            ).use { statement ->
                statement.setString(1, candidate)
                statement.setString(2, version)
                statement.setString(3, platform)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) read(rows) else null }.toList()
                }
            }
    }

private fun tagsOf(
    candidate: String,
    version: String,
    platform: String,
): List<String> = queryTagsOf(candidate, version, platform, "tag") { it.getString("tag") }

private fun lastUpdatedAt(
    candidate: String,
    version: String,
    platform: String,
): LocalDateTime =
    queryTagsOf(candidate, version, platform, "last_updated_at") {
        it.getTimestamp("last_updated_at").toLocalDateTime()
    }.single()
