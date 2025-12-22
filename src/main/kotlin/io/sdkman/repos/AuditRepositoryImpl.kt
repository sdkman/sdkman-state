package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.AuditRepository
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

class AuditRepositoryImpl : AuditRepository {

    private object VendorAuditTable : Table(name = "vendor_audit") {
        val id = long("id").autoIncrement()
        val username = text("username")
        val timestamp = timestamp("timestamp")
        val operation = text("operation")
        val versionData = json<Version>("version_data", Json.Default)

        override val primaryKey = PrimaryKey(id)
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun recordAudit(
        username: String,
        operation: AuditOperation,
        version: Version
    ): Either<DatabaseFailure, Unit> = Either.catch {
        dbQuery {
            VendorAuditTable.insert {
                it[this.username] = username
                it[this.timestamp] = Instant.now()
                it[this.operation] = operation.name
                it[this.versionData] = version
            }
        }
        Unit
    }.mapLeft { error ->
        DatabaseFailure(
            message = "Failed to record audit: ${error.message}",
            cause = error
        )
    }
}
