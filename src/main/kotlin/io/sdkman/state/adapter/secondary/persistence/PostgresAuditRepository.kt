package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.AuditContext
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Auditable
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.repository.AuditRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.json
import java.time.Instant

internal object AuditTable : Table(name = "vendor_audit") {
    val id = long("id").autoIncrement()
    val vendorId = uuid("vendor_id")
    val email = text("email")
    val timestamp = timestamp("timestamp")
    val operation = text("operation")
    val versionData = json<JsonElement>("version_data", Json.Default)

    override val primaryKey = PrimaryKey(id)
}

class PostgresAuditRepository : AuditRepository {
    private fun Auditable.toJsonElement(): JsonElement =
        when (this) {
            is Version -> Json.encodeToJsonElement(this.toAuditData())
            is UniqueTag -> Json.encodeToJsonElement(this.toAuditData())
        }

    override suspend fun recordAudit(
        context: AuditContext,
        operation: AuditOperation,
        data: Auditable,
    ): Either<DatabaseFailure, Unit> =
        Either
            .catch {
                dbQuery {
                    AuditTable.insert {
                        it[this.vendorId] = context.vendorId
                        it[this.email] = context.email
                        it[this.timestamp] = Instant.now()
                        it[this.operation] = operation.name
                        it[this.versionData] = data.toJsonElement()
                    }
                }
                Unit
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to record audit: ${error.message}",
                    cause = error,
                )
            }
}
