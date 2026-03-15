package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.some
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.VersionTag
import io.sdkman.state.domain.repository.TagsRepository
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

internal object VersionTagsTable : IntIdTable("version_tags") {
    val candidate = text("candidate")
    val tag = text("tag")
    val distribution = text("distribution")
    val platform = text("platform")
    val versionId = integer("version_id")
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")

    init {
        uniqueIndex(candidate, tag, distribution, platform)
    }
}

class PostgresTagRepository : TagsRepository {
    private fun distributionToDb(distribution: Option<Distribution>): String = distribution.map { it.name }.getOrElse { NA_SENTINEL }

    private fun dbToDistribution(value: String): Option<Distribution> =
        if (value == NA_SENTINEL) None else Distribution.valueOf(value).some()

    private fun ResultRow.toVersionTag(): VersionTag =
        VersionTag(
            id = this[VersionTagsTable.id].value,
            candidate = this[VersionTagsTable.candidate],
            tag = this[VersionTagsTable.tag],
            distribution = dbToDistribution(this[VersionTagsTable.distribution]),
            platform = Platform.valueOf(this[VersionTagsTable.platform]),
            versionId = this[VersionTagsTable.versionId],
            createdAt = this[VersionTagsTable.createdAt].toKotlinTimeInstant(),
            lastUpdatedAt = this[VersionTagsTable.lastUpdatedAt].toKotlinTimeInstant(),
        )

    override suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .selectAll()
                        .where { VersionTagsTable.versionId eq versionId }
                        .map { it.toVersionTag() }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find tags by version ID: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findVersionIdByTag(
        candidate: String,
        tag: String,
        distribution: Option<Distribution>,
        platform: Platform,
    ): Either<DatabaseFailure, Option<Int>> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .selectAll()
                        .where {
                            (VersionTagsTable.candidate eq candidate) and
                                (VersionTagsTable.tag eq tag) and
                                (VersionTagsTable.distribution eq distributionToDb(distribution)) and
                                (VersionTagsTable.platform eq platform.name)
                        }.map { it[VersionTagsTable.versionId] }
                        .firstOrNone()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find version ID by tag: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun replaceTags(
        versionId: Int,
        candidate: String,
        distribution: Option<Distribution>,
        platform: Platform,
        tags: List<String>,
    ): Either<DatabaseFailure, Unit> =
        Either
            .catch {
                dbQuery {
                    val distDb = distributionToDb(distribution)
                    val platformDb = platform.name

                    // Remove all existing tags for this version in this scope
                    VersionTagsTable.deleteWhere {
                        (VersionTagsTable.versionId eq versionId) and
                            (VersionTagsTable.candidate eq candidate) and
                            (VersionTagsTable.distribution eq distDb) and
                            (VersionTagsTable.platform eq platformDb)
                    }

                    // For each tag, remove from other versions in the same scope and insert
                    tags.forEach { tagName ->
                        VersionTagsTable.deleteWhere {
                            (VersionTagsTable.candidate eq candidate) and
                                (VersionTagsTable.tag eq tagName) and
                                (VersionTagsTable.distribution eq distDb) and
                                (VersionTagsTable.platform eq platformDb)
                        }

                        VersionTagsTable.insert {
                            it[this.candidate] = candidate
                            it[this.tag] = tagName
                            it[this.distribution] = distDb
                            it[this.platform] = platformDb
                            it[this.versionId] = versionId
                            it[this.createdAt] = Instant.now()
                            it[this.lastUpdatedAt] = Instant.now()
                        }
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to replace tags: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable.deleteWhere {
                        (candidate eq uniqueTag.candidate) and
                            (tag eq uniqueTag.tag) and
                            (distribution eq distributionToDb(uniqueTag.distribution)) and
                            (platform eq uniqueTag.platform.name)
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete tag: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .selectAll()
                        .where { VersionTagsTable.versionId eq versionId }
                        .count() > 0
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to check tags for version: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .select(VersionTagsTable.tag)
                        .where { VersionTagsTable.versionId eq versionId }
                        .map { it[VersionTagsTable.tag] }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find tag names by version ID: ${error.message}",
                    cause = error,
                )
            }
}
