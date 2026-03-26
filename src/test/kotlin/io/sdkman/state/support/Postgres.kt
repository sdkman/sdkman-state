package io.sdkman.state.support

import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.toOption
import io.sdkman.state.adapter.secondary.persistence.AuditTable
import io.sdkman.state.adapter.secondary.persistence.AuditVersionData
import io.sdkman.state.adapter.secondary.persistence.NA_SENTINEL
import io.sdkman.state.adapter.secondary.persistence.VendorsTable
import io.sdkman.state.adapter.secondary.persistence.VersionTagsTable
import io.sdkman.state.adapter.secondary.persistence.VersionsTable
import io.sdkman.state.adapter.secondary.persistence.toDomain
import io.sdkman.state.config.DefaultAppConfig
import io.sdkman.state.config.jdbcUrl
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

val DB_HOST: String get() = PostgresTestContainer.host
val DB_PORT: Int get() = PostgresTestContainer.port
val DB_USERNAME: String get() = PostgresTestContainer.username
val DB_PASSWORD: String get() = PostgresTestContainer.password

data class VendorAuditRecord(
    val id: Long = 0,
    val vendorId: java.util.UUID,
    val email: String,
    val timestamp: kotlin.time.Instant,
    val operation: AuditOperation,
    val versionData: String,
)

fun insertVersions(vararg cvs: Version) =
    transaction {
        cvs.forEach { cv ->
            VersionsTable.insert {
                it[candidate] = cv.candidate
                it[version] = cv.version
                it[platform] = cv.platform.name
                it[distribution] = cv.distribution.map { dist -> dist.name }.getOrNull()
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
        VersionsTable
            .insert {
                it[candidate] = cv.candidate
                it[version] = cv.version
                it[platform] = cv.platform.name
                it[distribution] = cv.distribution.map { dist -> dist.name }.getOrNull()
                it[url] = cv.url
                it[visible] = cv.visible.getOrElse { true }
                it[md5sum] = cv.md5sum.getOrNull()
                it[sha256sum] = cv.sha256sum.getOrNull()
                it[sha512sum] = cv.sha512sum.getOrNull()
            }[VersionsTable.id]
            .value
    }

fun insertTag(
    candidate: String,
    tag: String,
    distribution: Option<Distribution>,
    platform: Platform,
    versionId: Int,
) = transaction {
    VersionTagsTable.insert {
        it[this.candidate] = candidate
        it[this.tag] = tag
        it[this.distribution] = distribution.map { dist -> dist.name }.getOrElse { NA_SENTINEL }
        it[this.platform] = platform.name
        it[this.versionId] = versionId
        it[this.createdAt] = Instant.now()
        it[this.lastUpdatedAt] = Instant.now()
    }
}

fun selectTagNames(versionId: Int): List<String> =
    dbQuery {
        VersionTagsTable
            .selectAll()
            .where { VersionTagsTable.versionId eq versionId }
            .map { it[VersionTagsTable.tag] }
    }

fun selectAllTags(): List<Pair<Int, String>> =
    dbQuery {
        VersionTagsTable
            .selectAll()
            .map { it[VersionTagsTable.versionId] to it[VersionTagsTable.tag] }
    }

@Suppress("InjectDispatcher")
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
        VersionsTable
            .selectAll()
            .where {
                (VersionsTable.candidate eq candidate) and
                    (VersionsTable.version eq version) and
                    distribution.fold({ VersionsTable.distribution eq null }, { VersionsTable.distribution eq it.name }) and
                    (VersionsTable.platform eq platform.name)
            }.map {
                Version(
                    candidate = it[VersionsTable.candidate],
                    version = it[VersionsTable.version],
                    distribution = it[VersionsTable.distribution].toOption().map { dist -> Distribution.valueOf(dist) },
                    platform = Platform.valueOf(it[VersionsTable.platform]),
                    url = it[VersionsTable.url],
                    visible = it[VersionsTable.visible].toOption(),
                    md5sum = it[VersionsTable.md5sum].toOption(),
                    sha256sum = it[VersionsTable.sha256sum].toOption(),
                    sha512sum = it[VersionsTable.sha512sum].toOption(),
                )
            }.firstOrNone()
    }

fun selectLastUpdatedAt(
    candidate: String,
    version: String,
    distribution: Option<Distribution>,
    platform: Platform,
): Option<kotlin.time.Instant> =
    dbQuery {
        VersionsTable
            .selectAll()
            .where {
                (VersionsTable.candidate eq candidate) and
                    (VersionsTable.version eq version) and
                    distribution.fold({ VersionsTable.distribution eq null }, { VersionsTable.distribution eq it.name }) and
                    (VersionsTable.platform eq platform.name)
            }.map {
                val javaInstant = it[VersionsTable.lastUpdatedAt]
                kotlin.time.Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano.toLong())
            }.firstOrNone()
    }

private val testAppConfig by lazy { DefaultAppConfig(testApplicationConfig()) }

private fun initialisePostgres() =
    Database
        .connect(
            url = testAppConfig.jdbcUrl,
            user = DB_USERNAME,
            password = DB_PASSWORD,
            driver = "org.postgresql.Driver",
        ).also {
            Flyway
                .configure()
                .dataSource(
                    testAppConfig.jdbcUrl,
                    DB_USERNAME,
                    DB_PASSWORD,
                ).load()
                .migrate()
        }

fun selectAuditRecords(): List<VendorAuditRecord> =
    dbQuery {
        AuditTable.selectAll().map { row ->
            VendorAuditRecord(
                id = row[AuditTable.id],
                vendorId = row[AuditTable.vendorId],
                email = row[AuditTable.email],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[AuditTable.timestamp].epochSecond,
                        row[AuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[AuditTable.operation]),
                versionData = row[AuditTable.versionData].toString(),
            )
        }
    }

fun selectAuditRecordsByEmail(email: String): List<VendorAuditRecord> =
    dbQuery {
        AuditTable.selectAll().where { AuditTable.email eq email }.map { row ->
            VendorAuditRecord(
                id = row[AuditTable.id],
                vendorId = row[AuditTable.vendorId],
                email = row[AuditTable.email],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[AuditTable.timestamp].epochSecond,
                        row[AuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[AuditTable.operation]),
                versionData = row[AuditTable.versionData].toString(),
            )
        }
    }

fun selectAuditRecordsByOperation(operation: AuditOperation): List<VendorAuditRecord> =
    dbQuery {
        AuditTable.selectAll().where { AuditTable.operation eq operation.name }.map { row ->
            VendorAuditRecord(
                id = row[AuditTable.id],
                vendorId = row[AuditTable.vendorId],
                email = row[AuditTable.email],
                timestamp =
                    kotlin.time.Instant.fromEpochSeconds(
                        row[AuditTable.timestamp].epochSecond,
                        row[AuditTable.timestamp].nano.toLong(),
                    ),
                operation = AuditOperation.valueOf(row[AuditTable.operation]),
                versionData = row[AuditTable.versionData].toString(),
            )
        }
    }

fun deserializeVersionData(versionData: String): Version = Json.decodeFromString(AuditVersionData.serializer(), versionData).toDomain()

fun withCleanDatabase(fn: suspend () -> Unit) {
    initialisePostgres()
    transaction {
        VersionTagsTable.deleteAll()
        AuditTable.deleteAll()
        VersionsTable.deleteAll()
        VendorsTable.deleteAll()
    }
    runBlocking { fn() }
}
