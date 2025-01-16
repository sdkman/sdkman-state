package io.sdkman.repos

import arrow.core.toOption
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

class VersionsRepository {

    private object Versions : IntIdTable(name = "versions") {
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

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun read(candidate: String): List<Version> = dbQuery {
        Versions.select { Versions.candidate eq candidate }
            .map {
                Version(
                    candidate = it[Versions.candidate],
                    version = it[Versions.version],
                    vendor = it[Versions.vendor],
                    platform = it[Versions.platform],
                    url = it[Versions.url],
                    visible = it[Versions.visible],
                    md5sum = it[Versions.md5sum].toOption(),
                    sha256sum = it[Versions.sha256sum].toOption(),
                    sha512sum = it[Versions.sha512sum].toOption(),
                )
            }
            .sortedWith(compareBy({ it.candidate }, { it.version }, { it.vendor }, { it.platform }))
    }

    suspend fun read(candidate: String, platform: String): List<Version> = dbQuery {
        Versions.select { (Versions.candidate eq candidate) and (Versions.platform eq platform) }
            .map {
                Version(
                    candidate = it[Versions.candidate],
                    version = it[Versions.version],
                    vendor = it[Versions.vendor],
                    platform = it[Versions.platform],
                    url = it[Versions.url],
                    visible = it[Versions.visible],
                    md5sum = it[Versions.md5sum].toOption(),
                    sha256sum = it[Versions.sha256sum].toOption(),
                    sha512sum = it[Versions.sha512sum].toOption(),
                )
            }
            .sortedWith(compareBy({ it.candidate }, { it.version }, { it.vendor }, { it.platform }))
    }

    fun create(cv: Version): InsertStatement<Number> = transaction {
        Versions.insert {
            it[candidate] = cv.candidate
            it[version] = cv.version
            it[vendor] = cv.vendor
            it[platform] = cv.platform
            it[url] = cv.url
            it[visible] = cv.visible
            it[md5sum] = cv.md5sum.getOrNull()
            it[sha256sum] = cv.sha256sum.getOrNull()
            it[sha512sum] = cv.sha512sum.getOrNull()
        }
    }

    fun delete(version: UniqueVersion): Int = transaction {
        Versions.deleteWhere {
            (candidate eq version.candidate) and
                    (this.version eq version.version) and
                    (vendor eq version.vendor) and
                    (platform eq version.platform)
        }
    }
}
