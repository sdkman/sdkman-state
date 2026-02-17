package io.sdkman.repos

import arrow.core.Either
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.right
import arrow.core.some
import arrow.core.toOption
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class VersionsRepository {
    private object Versions : IntIdTable(name = "versions") {
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

    private object VersionTags : Table("version_tags") {
        val versionId = integer("version_id")
        val tag = text("tag")
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    private fun ResultRow.toVersion(): Version =
        Version(
            candidate = this[Versions.candidate],
            version = this[Versions.version],
            platform = Platform.valueOf(this[Versions.platform]),
            url = this[Versions.url],
            visible = this[Versions.visible].toOption(),
            distribution = this[Versions.distribution].toOption().map { Distribution.valueOf(it) },
            md5sum = this[Versions.md5sum].toOption(),
            sha256sum = this[Versions.sha256sum].toOption(),
            sha512sum = this[Versions.sha512sum].toOption(),
        )

    private fun Version.withTags(tags: List<String>): Version =
        copy(tags = tags.some())

    private fun fetchTagNames(versionId: Int): List<String> =
        VersionTags
            .select(VersionTags.tag)
            .where { VersionTags.versionId eq versionId }
            .map { it[VersionTags.tag] }

    private fun batchFetchTags(versionIds: List<Int>): Map<Int, List<String>> =
        if (versionIds.isEmpty()) emptyMap()
        else
            VersionTags
                .select(VersionTags.versionId, VersionTags.tag)
                .where { VersionTags.versionId inList versionIds }
                .groupBy { it[VersionTags.versionId] }
                .mapValues { (_, rows) -> rows.map { it[VersionTags.tag] } }

    private fun matchesVersion(cv: Version): Op<Boolean> =
        (Versions.candidate eq cv.candidate) and
            (Versions.version eq cv.version) and
            (cv.distribution.fold({ Versions.distribution eq null }, { Versions.distribution eq it.name })) and
            (Versions.platform eq cv.platform.name)

    suspend fun read(
        candidate: String,
        platform: Option<Platform>,
        distribution: Option<Distribution>,
        visible: Option<Boolean>,
    ): List<Version> =
        dbQuery {
            val rows =
                Versions
                    .selectAll()
                    .where {
                        (Versions.candidate eq candidate) and
                            platform.map { Versions.platform eq it.name }.getOrElse { Op.TRUE } and
                            distribution.fold({ Op.TRUE }, { Versions.distribution eq it.name }) and
                            visible.map { Versions.visible eq it }.getOrElse { Op.TRUE }
                    }.map { it[Versions.id].value to it.toVersion() }

            val tagsByVersionId = batchFetchTags(rows.map { it.first })

            rows
                .map { (id, version) -> version.withTags(tagsByVersionId.getOrDefault(id, emptyList())) }
                .sortedWith(compareBy({ it.candidate }, { it.version }, { it.distribution.getOrNull() }, { it.platform }))
        }

    suspend fun read(
        candidate: String,
        version: String,
        platform: Platform,
        distribution: Option<Distribution>,
    ): Option<Version> =
        dbQuery {
            Versions
                .selectAll()
                .where {
                    (Versions.candidate eq candidate) and
                        (Versions.version eq version) and
                        (Versions.platform eq platform.name) and
                        distribution.fold({ Versions.distribution eq null }, { Versions.distribution eq it.name })
                }.map { it[Versions.id].value to it.toVersion() }
                .firstOrNone()
                .map { (id, v) -> v.withTags(fetchTagNames(id)) }
        }

    fun create(cv: Version): Either<String, Unit> =
        transaction {
            val exists =
                Versions
                    .selectAll()
                    .where { matchesVersion(cv) }
                    .empty()
                    .not()

            if (exists) updateVersion(cv) else insertVersion(cv)
        }

    private fun updateVersion(cv: Version): Either<String, Unit> {
        Versions.update({ matchesVersion(cv) }) {
            it[url] = cv.url
            it[visible] = cv.visible.getOrElse { true }
            it[md5sum] = cv.md5sum.getOrNull()
            it[sha256sum] = cv.sha256sum.getOrNull()
            it[sha512sum] = cv.sha512sum.getOrNull()
            it[lastUpdatedAt] = Instant.now()
        }
        return Unit.right()
    }

    private fun insertVersion(cv: Version): Either<String, Unit> {
        Versions.insert {
            it[candidate] = cv.candidate
            it[version] = cv.version
            it[distribution] = cv.distribution.map { d -> d.name }.getOrNull()
            it[platform] = cv.platform.name
            it[url] = cv.url
            it[visible] = cv.visible.getOrElse { true }
            it[md5sum] = cv.md5sum.getOrNull()
            it[sha256sum] = cv.sha256sum.getOrNull()
            it[sha512sum] = cv.sha512sum.getOrNull()
        }
        return Unit.right()
    }

    fun delete(version: UniqueVersion): Int =
        transaction {
            Versions.deleteWhere {
                val baseCondition =
                    (candidate eq version.candidate) and
                        (this.version eq version.version) and
                        (platform eq version.platform.name)

                version.distribution.fold(
                    { baseCondition and (distribution eq null) },
                    { distributionValue -> baseCondition and (distribution eq distributionValue.name) },
                )
            }
        }
}