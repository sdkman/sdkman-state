package io.sdkman.support

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

fun initialisePostgres() = Database.connect(
    url = "jdbc:postgresql://localhost:5432/sdkman?sslMode=prefer&loglevel=2",
    user = "postgres",
    password = "postgres",
    driver = "org.postgresql.Driver"
)

@Serializable
data class CandidateVersion(
    val candidate: String,
    val version: String,
    val platform: String,
    val vendor: String? = null,
    val url: String,
    val visible: Boolean,
    val md5sum: String? = null,
    val sha256sum: String? = null,
    val sha512sum: String? = null
)

private object CandidateVersions : IntIdTable(name = "version") {
    val candidate = varchar("candidate", length = 20)
    val version = varchar("version", length = 25)
    val platform = varchar("platform", length = 15)
    val vendor = varchar("vendor", length = 10).nullable()
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