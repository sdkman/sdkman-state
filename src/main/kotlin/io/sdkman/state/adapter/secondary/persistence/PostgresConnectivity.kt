package io.sdkman.state.adapter.secondary.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

const val NA_SENTINEL = "NA"

internal object Versions : IntIdTable(name = "versions") {
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

internal object VersionTags : IntIdTable("version_tags") {
    val candidate = text("candidate")
    val tag = text("tag")
    val distribution = text("distribution")
    val platform = text("platform")
    val versionId = integer("version_id")
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")

    init {
        uniqueIndex(candidate, tag, distribution, platform)
    }
}

internal object VendorAuditTable : Table(name = "vendor_audit") {
    val id = long("id").autoIncrement()
    val username = text("username")
    val timestamp = timestamp("timestamp")
    val operation = text("operation")
    val versionData = json<JsonElement>("version_data", Json.Default)

    override val primaryKey = PrimaryKey(id)
}

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

fun java.time.Instant.toKotlinTimeInstant(): kotlin.time.Instant = kotlin.time.Instant.fromEpochSeconds(epochSecond, nano.toLong())
