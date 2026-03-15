package io.sdkman.repos

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

const val NA_SENTINEL = "NA"

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

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
