package io.sdkman.repos

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.some
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.TagsRepository
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.VersionTag
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

class TagsRepositoryImpl : TagsRepository {
    private fun distributionToDb(distribution: Option<Distribution>): String = distribution.map { it.name }.getOrElse { NA_SENTINEL }

    private fun dbToDistribution(value: String): Option<Distribution> =
        if (value == NA_SENTINEL) None else Distribution.valueOf(value).some()

    private fun ResultRow.toVersionTag(): VersionTag =
        VersionTag(
            id = this[VersionTags.id].value,
            candidate = this[VersionTags.candidate],
            tag = this[VersionTags.tag],
            distribution = dbToDistribution(this[VersionTags.distribution]),
            platform = Platform.valueOf(this[VersionTags.platform]),
            versionId = this[VersionTags.versionId],
            createdAt = this[VersionTags.createdAt],
            lastUpdatedAt = this[VersionTags.lastUpdatedAt],
        )

    override suspend fun findTagsByVersionId(versionId: Int): Either<DatabaseFailure, List<VersionTag>> =
        Either
            .catch {
                dbQuery {
                    VersionTags
                        .selectAll()
                        .where { VersionTags.versionId eq versionId }
                        .map { it.toVersionTag() }
                }
            }.mapLeft { error ->
                DatabaseFailure(
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
                    VersionTags
                        .selectAll()
                        .where {
                            (VersionTags.candidate eq candidate) and
                                (VersionTags.tag eq tag) and
                                (VersionTags.distribution eq distributionToDb(distribution)) and
                                (VersionTags.platform eq platform.name)
                        }.map { it[VersionTags.versionId] }
                        .firstOrNone()
                }
            }.mapLeft { error ->
                DatabaseFailure(
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
                    VersionTags.deleteWhere {
                        (VersionTags.versionId eq versionId) and
                            (VersionTags.candidate eq candidate) and
                            (VersionTags.distribution eq distDb) and
                            (VersionTags.platform eq platformDb)
                    }

                    // For each tag, remove from other versions in the same scope and insert
                    tags.forEach { tagName ->
                        VersionTags.deleteWhere {
                            (VersionTags.candidate eq candidate) and
                                (VersionTags.tag eq tagName) and
                                (VersionTags.distribution eq distDb) and
                                (VersionTags.platform eq platformDb)
                        }

                        VersionTags.insert {
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
                DatabaseFailure(
                    message = "Failed to replace tags: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun deleteTag(uniqueTag: UniqueTag): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    VersionTags.deleteWhere {
                        (candidate eq uniqueTag.candidate) and
                            (tag eq uniqueTag.tag) and
                            (distribution eq distributionToDb(uniqueTag.distribution)) and
                            (platform eq uniqueTag.platform.name)
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure(
                    message = "Failed to delete tag: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun hasTagsForVersion(versionId: Int): Either<DatabaseFailure, Boolean> =
        Either
            .catch {
                dbQuery {
                    VersionTags
                        .selectAll()
                        .where { VersionTags.versionId eq versionId }
                        .count() > 0
                }
            }.mapLeft { error ->
                DatabaseFailure(
                    message = "Failed to check tags for version: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findTagNamesByVersionId(versionId: Int): Either<DatabaseFailure, List<String>> =
        Either
            .catch {
                dbQuery {
                    VersionTags
                        .select(VersionTags.tag)
                        .where { VersionTags.versionId eq versionId }
                        .map { it[VersionTags.tag] }
                }
            }.mapLeft { error ->
                DatabaseFailure(
                    message = "Failed to find tag names by version ID: ${error.message}",
                    cause = error,
                )
            }
}
