package io.sdkman.support

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.toOption
import io.sdkman.config.DatabaseConfig
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.repos.NA_SENTINEL
import io.sdkman.repos.VersionTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

const val DB_HOST = "localhost"
const val DB_PORT = 5432
const val DB_USERNAME = "postgres"
const val DB_PASSWORD = "postgres"

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

private object VendorAuditTable : Table(name = "vendor_audit") {
    val id = long("id").autoIncrement()
    val username = text("username")
    val timestamp = timestamp("timestamp")
    val operation = text("operation")
    val versionData = json<JsonElement>("version_data", Json.Default)

    override val primaryKey = PrimaryKey(id)
}

data class VendorAuditRecord(
    val id: Long = 0,
    val username: String,
    val timestamp: kotlin.time.Instant,
    val operation: AuditOperation,
    val versionData: String,
)

fun insertVersions(vararg cvs: Version) =
    transaction {
        cvs.forEach { cv ->
            Versions.insert {
                it[candidate] = cv.candidate
                it[version] = cv.version
                it[platform] = cv.platform.name
                it[distribution] = cv.distribution.map { it.name }.getOrNull()
                it[url] = cv.url
                it[visible] = cv.visible.getOrElse { true }
                it[md5sum] = cv.md5sum.getOrNull()
                it[sha256sum] = cv.sha256sum.getOrNull()
                it[sha512sum] = cv.sha512sum.getOrNull()
            }
        }
    }

fun insertVersionWithId(cv: Version): Int =
    transaction {
        Versions
            .insert {
                it[candidate] = cv.candidate
                it[version] = cv.version
                it[platform] = cv.platform.name
                it[distribution] = cv.distribution.map { it.name }.getOrNull()
                it[url] = cv.url
                it[visible] = cv.visible.getOrElse { true }
                it[md5sum] = cv.md5sum.getOrNull()
                it[sha256sum] = cv.sha256sum.getOrNull()
                it[sha512sum] = cv.sha512sum.getOrNull()
            }[Versions.id]
            .value
    }

fun insertTag(
    candidate: String,
    tag: String,
    distribution: Option<Distribution>,
    platform: Platform,
    versionId: Int,
) = transaction {
    VersionTags.insert {
        it[this.candidate] = candidate
        it[this.tag] = tag
        it[this.distribution] = distribution.map { it.name }.getOrElse { NA_SENTINEL }
        it[this.platform] = platform.name
        it[this.versionId] = versionId
        it[this.createdAt] = Instant.now()
        it[this.lastUpdatedAt] = Instant.now()
    }
}

fun selectTagNames(versionId: Int): List<String> =
    dbQuery {
        VersionTags
            .selectAll()
            .where { VersionTags.versionId eq versionId }
            .map { it[VersionTags.tag] }
    }

fun selectAllTags(): List<Pair<Int, String>> =
    dbQuery {
        VersionTags
            .selectAll()
            .map { it[VersionTags.versionId] to it[VersionTags.tag] }
    }

private fun <T> dbQuery(block: suspend () -> T): T =
    runBlocking(Dispatchers.IO) {
        newSuspendedTransaction(Dispatchers.IO) { block() }
    }

fun selectVersion(
    candidate: String,
    version: String,
    distribution: Option<Distribution>,
    platform: Platform,
): Option<Version> =
    dbQuery {
        Versions
            .selectAll()
            .where {
                (Versions.candidate eq candidate) and
                    (Versions.version eq version) and
                    distribution.fold({ Versions.distribution eq null }, { Versions.distribution eq it.name }) and
                    (Versions.platform eq platform.name)
            }.map {
                Version(
                    candidate = it[Versions.candidate],
                    version = it[Versions.version],
                    distribution = it[Versions.distribution].toOption().map { Distribution.valueOf(it) },
                    platform = Platform.valueOf(it[Versions.platform]),
                    url = it[Versions.url],
                    visible = it[Versions.visible].toOption(),
                    md5sum = it[Versions.md5sum].toOption(),
                    sha256sum = it[Versions.sha256sum].toOption(),
                    sha512sum = it[Versions.sha512sum].toOption(),
                )
            }.firstOrNone()
    }

fun selectLastUpdatedAt(
    candidate: String,
    version: String,
    distribution: Option<Distribution>,
    platform: Platform,
): Option<Instant> =
    dbQuery {
        Versions
            .selectAll()
            .where {
                (Versions.candidate eq candidate) and
                    (Versions.version eq version) and
                    distribution.fold({ Versions.distribution eq null }, { Versions.distribution eq it.name }) and
                    (Versions.platform eq platform.name)
            }.map {
                it[Versions.lastUpdatedAt]
            }.firstOrNone()
    }

private val testDbConfig = DatabaseConfig(DB_HOST, DB_PORT, DB_USERNAME, DB_PASSWORD)

private fun initialisePostgres() =
    Database
        .connect(
            url = testDbConfig.jdbcUrl,
            user = DB_USERNAME,
            password = DB_PASSWORD,
            driver = "org.postgresql.Driver",
        ).also {
            Flyway
                .configure()
                .dataSource(
                    testDbConfig.jdbcUrl,
                    DB_USERNAME,
                    DB_PASSWORD,
                ).load()
                .migrate()
        }

fun selectAuditRecords(): List<VendorAuditRecord> =
    dbQuery {
        VendorAuditTable.selectAll().map { row ->
            VendorAuditRecord(
                id = row[VendorAuditTable.id],
                username = row[VendorAuditTable.username],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[VendorAuditTable.timestamp].epochSecond,
                        row[VendorAuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[VendorAuditTable.operation]),
                versionData = row[VendorAuditTable.versionData].toString(),
            )
        }
    }

fun selectAuditRecordsByUsername(username: String): List<VendorAuditRecord> =
    dbQuery {
        VendorAuditTable.selectAll().where { VendorAuditTable.username eq username }.map { row ->
            VendorAuditRecord(
                id = row[VendorAuditTable.id],
                username = row[VendorAuditTable.username],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[VendorAuditTable.timestamp].epochSecond,
                        row[VendorAuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[VendorAuditTable.operation]),
                versionData = row[VendorAuditTable.versionData].toString(),
            )
        }
    }

fun selectAuditRecordsByOperation(operation: AuditOperation): List<VendorAuditRecord> =
    dbQuery {
        VendorAuditTable.selectAll().where { VendorAuditTable.operation eq operation.name }.map { row ->
            VendorAuditRecord(
                id = row[VendorAuditTable.id],
                username = row[VendorAuditTable.username],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[VendorAuditTable.timestamp].epochSecond,
                        row[VendorAuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[VendorAuditTable.operation]),
                versionData = row[VendorAuditTable.versionData].toString(),
            )
        }
    }

fun deserializeVersionData(versionData: String): Version = Json.decodeFromString(Version.serializer(), versionData)

fun withCleanDatabase(fn: suspend () -> Unit) {
    initialisePostgres()
    transaction {
        VersionTags.deleteAll()
        VendorAuditTable.deleteAll()
        Versions.deleteAll()
    }
    runBlocking { fn() }
}
