package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.AuditOperation
import io.sdkman.domain.AuditRepository
import io.sdkman.domain.Auditable
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import java.time.Instant

class AuditRepositoryImpl : AuditRepository {
    private object VendorAuditTable : Table(name = "vendor_audit") {
        val id = long("id").autoIncrement()
        val username = text("username")
        val timestamp = timestamp("timestamp")
        val operation = text("operation")
        val versionData = json<JsonElement>("version_data", Json.Default)

        override val primaryKey = PrimaryKey(id)
    }

    private fun Auditable.toJsonElement(): JsonElement =
        when (this) {
            is Version -> Json.encodeToJsonElement(this)
            is UniqueTag -> Json.encodeToJsonElement(this)
        }

    override suspend fun recordAudit(
        username: String,
        operation: AuditOperation,
        data: Auditable,
    ): Either<DatabaseFailure, Unit> =
        Either
            .catch {
                dbQuery {
                    VendorAuditTable.insert {
                        it[this.username] = username
                        it[this.timestamp] = Instant.now()
                        it[this.operation] = operation.name
                        it[this.versionData] = data.toJsonElement()
                    }
                }
                Unit
            }.mapLeft { error ->
                DatabaseFailure(
                    message = "Failed to record audit: ${error.message}",
                    cause = error,
                )
            }
}
