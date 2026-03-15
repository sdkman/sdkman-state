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
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

internal object VersionsTable : IntIdTable(name = "versions") {
    val candidate = varchar("candidate", length = 20)
    val version = varchar("version", length = 25)
    val distribution = varchar("distribution", length = 20).nullable()
    val platform = varchar("platform", length = 15)
    val url = varchar("url", length = 500)
    val visible = bool("visible")
    val md5sum = varchar("md5_sum", length = 32).nullable()
    val sha256sum = varchar("sha_256_sum", length = 64).nullable()
    val sha512sum = varchar("sha_512_sum", length = 128).nullable()
    val lastUpdatedAt = timestamp("last_updated_at")
}

class PostgresVersionRepository : VersionRepository {
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

    private fun matchesVersion(cv: Version): Op<Boolean> =
        (VersionsTable.candidate eq cv.candidate) and
            (VersionsTable.version eq cv.version) and
            (cv.distribution.fold({ VersionsTable.distribution eq null }, { VersionsTable.distribution eq it.name })) and
            (VersionsTable.platform eq cv.platform.name)

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
                                distribution.fold(
                                    { VersionsTable.distribution eq null },
                                    { VersionsTable.distribution eq it.name },
                                )
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

    override suspend fun createOrUpdate(version: Version): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    val exists =
                        VersionsTable
                            .selectAll()
                            .where { matchesVersion(version) }
                            .empty()
                            .not()

                    if (exists) updateVersion(version) else insertVersion(version)
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to create version: ${error.message}",
                    cause = error,
                )
            }

    private fun updateVersion(cv: Version): Int {
        VersionsTable.update({ matchesVersion(cv) }) {
            it[url] = cv.url
            it[visible] = cv.visible.getOrElse { true }
            it[md5sum] = cv.md5sum.getOrNull()
            it[sha256sum] = cv.sha256sum.getOrNull()
            it[sha512sum] = cv.sha512sum.getOrNull()
            it[lastUpdatedAt] = Instant.now()
        }
        return VersionsTable
            .select(VersionsTable.id)
            .where { matchesVersion(cv) }
            .single()[VersionsTable.id]
            .value
    }

    private fun insertVersion(cv: Version): Int =
        VersionsTable
            .insertAndGetId {
                it[candidate] = cv.candidate
                it[version] = cv.version
                it[distribution] = cv.distribution.map { d -> d.name }.getOrNull()
                it[platform] = cv.platform.name
                it[url] = cv.url
                it[visible] = cv.visible.getOrElse { true }
                it[md5sum] = cv.md5sum.getOrNull()
                it[sha256sum] = cv.sha256sum.getOrNull()
                it[sha512sum] = cv.sha512sum.getOrNull()
            }.value

    override suspend fun findVersionId(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Option<Int>> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .select(VersionsTable.id)
                        .where {
                            val baseCondition =
                                (VersionsTable.candidate eq uniqueVersion.candidate) and
                                    (VersionsTable.version eq uniqueVersion.version) and
                                    (VersionsTable.platform eq uniqueVersion.platform.name)
                            uniqueVersion.distribution.fold(
                                { baseCondition and (VersionsTable.distribution eq null) },
                                { distributionValue ->
                                    baseCondition and (VersionsTable.distribution eq distributionValue.name)
                                },
                            )
                        }.map { it[VersionsTable.id].value }
                        .firstOrNone()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find version ID: ${error.message}",
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
                    val distDb = distribution.map { it.name }.getOrElse { NA_SENTINEL }
                    VersionTagsTable
                        .selectAll()
                        .where {
                            (VersionTagsTable.candidate eq candidate) and
                                (VersionTagsTable.tag eq tag) and
                                (VersionTagsTable.distribution eq distDb) and
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

    override suspend fun delete(uniqueVersion: UniqueVersion): Either<DatabaseFailure, Int> =
        Either
            .catch {
                dbQuery {
                    VersionsTable.deleteWhere {
                        val baseCondition =
                            (candidate eq uniqueVersion.candidate) and
                                (this.version eq uniqueVersion.version) and
                                (platform eq uniqueVersion.platform.name)

                        uniqueVersion.distribution.fold(
                            { baseCondition and (distribution eq null) },
                            { distributionValue -> baseCondition and (distribution eq distributionValue.name) },
                        )
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete version: ${error.message}",
                    cause = error,
                )
            }
}
