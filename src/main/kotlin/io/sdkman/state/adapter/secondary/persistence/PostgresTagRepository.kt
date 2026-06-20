package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.toOption
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.VersionTag
import io.sdkman.state.domain.repository.TagRepository
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

internal object VersionTagsTable : IntIdTable("version_tags") {
    val candidate = text("candidate")
    val tag = text("tag")
    val distribution = text("distribution").nullable()
    val platform = text("platform")
    val versionId = integer("version_id")
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")

    init {
        uniqueIndex(candidate, tag, distribution, platform)
    }
}

class PostgresTagRepository : TagRepository {
    private fun distributionEq(distribution: Option<Distribution>): Op<Boolean> =
        distribution.fold(
            { VersionTagsTable.distribution.isNull() },
            { VersionTagsTable.distribution eq it.name },
        )

    private fun ResultRow.toVersionTag(): VersionTag =
        VersionTag(
            id = this[VersionTagsTable.id].value,
            candidate = this[VersionTagsTable.candidate],
            tag = this[VersionTagsTable.tag],
            distribution = this[VersionTagsTable.distribution].toOption().map { Distribution.valueOf(it) },
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

    override suspend fun findTagNamesByVersionIds(versionIds: List<Int>): Either<DatabaseFailure, Map<Int, List<String>>> =
        Either
            .catch {
                dbQuery {
                    if (versionIds.isEmpty()) {
                        emptyMap()
                    } else {
                        VersionTagsTable
                            .select(VersionTagsTable.versionId, VersionTagsTable.tag)
                            .where { VersionTagsTable.versionId inList versionIds }
                            .groupBy { it[VersionTagsTable.versionId] }
                            .mapValues { (_, rows) -> rows.map { it[VersionTagsTable.tag] } }
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find tag names by version IDs: ${error.message}",
                    cause = error,
                )
            }

    // R5: replaceTags must be race-safe under concurrent same-payload writers. The previous
    // "delete this version's tags then insert each" had two race windows: (1) the per-tag delete
    // before insert, (2) the up-front delete-all before any insert. Both let a concurrent writer
    // observe an empty/partial state and either re-insert (duplicate key) or wipe a sibling
    // writer's just-inserted row. The replacement is:
    //   1. Delete only this version's tags that are *not* in the incoming list (so removal still
    //      works without touching tags we are about to keep).
    //   2. UPSERT each incoming tag on the `(candidate, tag, distribution, platform)` unique
    //      index — idempotent on same-payload re-inserts, and atomically re-points the row's
    //      `version_id` when the tag is being moved from another version in scope.
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
                    val distDb = distribution.map { it.name }.getOrNull()
                    val platformDb = platform.name

                    VersionTagsTable.deleteWhere {
                        val sameVersionScope =
                            (VersionTagsTable.versionId eq versionId) and
                                (VersionTagsTable.candidate eq candidate) and
                                distributionEq(distribution) and
                                (VersionTagsTable.platform eq platformDb)
                        if (tags.isEmpty()) {
                            sameVersionScope
                        } else {
                            sameVersionScope and (VersionTagsTable.tag notInList tags)
                        }
                    }

                    tags.forEach { tagName ->
                        VersionTagsTable.upsert(
                            VersionTagsTable.candidate,
                            VersionTagsTable.tag,
                            VersionTagsTable.distribution,
                            VersionTagsTable.platform,
                            onUpdateExclude =
                                listOf(
                                    VersionTagsTable.id,
                                    VersionTagsTable.candidate,
                                    VersionTagsTable.tag,
                                    VersionTagsTable.distribution,
                                    VersionTagsTable.platform,
                                    VersionTagsTable.createdAt,
                                ),
                        ) {
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
                            distributionEq(uniqueTag.distribution) and
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
