package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.some
import arrow.core.toOption
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.VersionRepository
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant

internal object VersionsTable : IntIdTable(name = "versions") {
    val candidate = varchar("candidate", length = 20)
    val version = varchar("version", length = 25)
    val distribution = text("distribution").nullable()
    val platform = varchar("platform", length = 15)
    val url = varchar("url", length = 500)
    val visible = bool("visible")
    val md5sum = varchar("md5_sum", length = 32).nullable()
    val sha256sum = varchar("sha_256_sum", length = 64).nullable()
    val sha512sum = varchar("sha_512_sum", length = 128).nullable()
    val lastUpdatedAt = timestamp("last_updated_at")
}

class PostgresVersionRepository : VersionRepository {
    private fun distributionEq(distribution: Option<Distribution>): Op<Boolean> =
        distribution.fold(
            { VersionsTable.distribution.isNull() },
            { VersionsTable.distribution eq it.name },
        )

    private fun ResultRow.toVersion(): Version =
        Version(
            candidate = this[VersionsTable.candidate],
            version = this[VersionsTable.version],
            platform = Platform.valueOf(this[VersionsTable.platform]),
            url = this[VersionsTable.url],
            visible = this[VersionsTable.visible].toOption(),
            distribution = this[VersionsTable.distribution].toOption().map { Distribution.valueOf(it) },
            md5sum = this[VersionsTable.md5sum].toOption(),
            sha256sum = this[VersionsTable.sha256sum].toOption(),
            sha512sum = this[VersionsTable.sha512sum].toOption(),
        )

    private fun Version.withTags(tags: List<String>): Version = copy(tags = tags.some())

    private fun fetchTagNames(versionId: Int): List<String> =
        VersionTagsTable
            .select(VersionTagsTable.tag)
            .where { VersionTagsTable.versionId eq versionId }
            .map { it[VersionTagsTable.tag] }

    private fun batchFetchTags(versionIds: List<Int>): Map<Int, List<String>> =
        if (versionIds.isEmpty()) {
            emptyMap()
        } else {
            VersionTagsTable
                .select(VersionTagsTable.versionId, VersionTagsTable.tag)
                .where { VersionTagsTable.versionId inList versionIds }
                .groupBy { it[VersionTagsTable.versionId] }
                .mapValues { (_, rows) -> rows.map { it[VersionTagsTable.tag] } }
        }

    override suspend fun findByCandidate(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): Either<DatabaseFailure, List<Version>> =
        Either
            .catch {
                dbQuery {
                    val rows =
                        VersionsTable
                            .selectAll()
                            .where {
                                (VersionsTable.candidate eq candidate) and
                                    platform.map { VersionsTable.platform eq it.name }.getOrElse { Op.TRUE } and
                                    distribution.fold({ Op.TRUE }, { VersionsTable.distribution eq it.name }) and
                                    visible.map { VersionsTable.visible eq it }.getOrElse { Op.TRUE }
                            }.map { it[VersionsTable.id].value to it.toVersion() }

                    val tagsByVersionId = batchFetchTags(rows.map { it.first })

                    rows
                        .map { (id, version) -> version.withTags(tagsByVersionId.getOrDefault(id, emptyList())) }
                        .sortedWith(
                            compareBy(
                                { it.candidate },
                                { it.version },
                                { it.distribution.map { d -> d.name }.getOrElse { "" } },
                                { it.platform },
                            ),
                        )
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find versions by candidate: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findUnique(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DatabaseFailure, Option<Version>> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .selectAll()
                        .where {
                            (VersionsTable.candidate eq candidate) and
                                (VersionsTable.version eq version) and
                                (VersionsTable.platform eq platform.name) and
                                distributionEq(distribution)
                        }.map { it[VersionsTable.id].value to it.toVersion() }
                        .firstOrNone()
                        .map { (id, v) -> v.withTags(fetchTagNames(id)) }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find unique version: ${error.message}",
                    cause = error,
                )
            }

    // V16 made versions.distribution nullable (None -> NULL) and recreated the unique
    // constraint as `UNIQUE NULLS NOT DISTINCT (candidate, version, distribution, platform)`.
    // Under NULLS NOT DISTINCT two NULL-distribution rows collide on the conflict target, so
    // `INSERT … ON CONFLICT … DO UPDATE` dedups every row, including the null-distribution
    // case. One UPSERT path covers both Some(d) and None inputs.
    override suspend fun createOrUpdate(version: Version): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .upsert(
                            VersionsTable.candidate,
                            VersionsTable.version,
                            VersionsTable.distribution,
                            VersionsTable.platform,
                            onUpdateExclude =
                                listOf(
                                    VersionsTable.id,
                                    VersionsTable.candidate,
                                    VersionsTable.version,
                                    VersionsTable.distribution,
                                    VersionsTable.platform,
                                ),
                        ) {
                            it[candidate] = version.candidate
                            it[this.version] = version.version
                            it[distribution] = version.distribution.map { it.name }.getOrNull()
                            it[platform] = version.platform.name
                            it[url] = version.url
                            it[visible] = version.visible.getOrElse { true }
                            it[md5sum] = version.md5sum.getOrNull()
                            it[sha256sum] = version.sha256sum.getOrNull()
                            it[sha512sum] = version.sha512sum.getOrNull()
                            it[lastUpdatedAt] = Instant.now()
                        }[VersionsTable.id]
                        .value
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to create version: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findVersionId(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Option<Int>> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .select(VersionsTable.id)
                        .where {
                            (VersionsTable.candidate eq uniqueVersion.candidate) and
                                (VersionsTable.version eq uniqueVersion.version) and
                                (VersionsTable.platform eq uniqueVersion.platform.name) and
                                distributionEq(uniqueVersion.distribution)
                        }.map { it[VersionsTable.id].value }
                        .firstOrNone()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find version ID: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findByTag(
        candidate: String,
        tag: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Either<DatabaseFailure, Option<Version>> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .join(
                            VersionsTable,
                            JoinType.INNER,
                            additionalConstraint = { VersionTagsTable.versionId eq VersionsTable.id },
                        ).selectAll()
                        .where {
                            (VersionTagsTable.candidate eq candidate) and
                                (VersionTagsTable.tag eq tag) and
                                distributionEq(distribution) and
                                (VersionTagsTable.platform eq platform.name)
                        }.map { it[VersionsTable.id].value to it.toVersion() }
                        .firstOrNone()
                        .map { (id, v) -> v.withTags(fetchTagNames(id)) }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find version by tag: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun delete(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    VersionsTable.deleteWhere {
                        (candidate eq uniqueVersion.candidate) and
                            (this.version eq uniqueVersion.version) and
                            (platform eq uniqueVersion.platform.name) and
                            distributionEq(uniqueVersion.distribution)
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete version: ${error.message}",
                    cause = error,
                )
            }
}
