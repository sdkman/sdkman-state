package io.sdkman.support

import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

const val dbHost = "localhost"
const val dbPort = 5432
const val dbUsername = "postgres"
const val dbPassword = "postgres"

fun initialisePostgres() =
    Database.connect(
        url = "jdbc:postgresql://$dbHost:$dbPort/sdkman?sslMode=prefer&loglevel=2",
        user = dbUsername,
        password = dbPassword,
        driver = "org.postgresql.Driver"
    ).also {
        Flyway.configure().dataSource(
            "jdbc:postgresql://$dbHost:$dbPort/sdkman?sslMode=prefer&loglevel=2", dbUsername, dbPassword
        ).load().migrate()
    }

@Serializable
data class CandidateVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
    val url: String,
    val visible: Boolean,
    val md5sum: String? = null,
    val sha256sum: String? = null,
    val sha512sum: String? = null,
)

private object CandidateVersions : IntIdTable(name = "versions") {
    val candidate = varchar("candidate", length = 20)
    val version = varchar("version", length = 25)
    val vendor = varchar("vendor", length = 10)
    val platform = varchar("platform", length = 15)
    val url = varchar("url", length = 500)
    val visible = bool("visible")
    val md5sum = varchar("md5_sum", length = 32).nullable()
    val sha256sum = varchar("sha_256_sum", length = 64).nullable()
    val sha512sum = varchar("sha_512_sum", length = 128).nullable()
}

fun deleteVersions() = transaction { CandidateVersions.deleteAll() }

fun insertVersions(vararg cvs: CandidateVersion) = transaction {
    cvs.forEach { cv ->
        CandidateVersions.insert {
            it[candidate] = cv.candidate
            it[version] = cv.version
            it[platform] = cv.platform
            it[vendor] = cv.vendor
            it[url] = cv.url
            it[visible] = cv.visible
            it[md5sum] = cv.md5sum
            it[sha256sum] = cv.sha256sum
            it[sha512sum] = cv.sha512sum
        }
    }
}