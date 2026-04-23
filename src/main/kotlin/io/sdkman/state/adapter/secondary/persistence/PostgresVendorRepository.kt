package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.toOption
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

internal class TextArrayColumnType : ColumnType<List<String>>() {
    override fun sqlType(): String = "TEXT[]"

    @Suppress("UNCHECKED_CAST")
    override fun valueFromDB(value: Any): List<String> =
        when (value) {
            is java.sql.Array -> (value.array as Array<String>).toList()
            else -> error("Unexpected value type for TEXT[]: ${value::class}")
        }

    override fun notNullValueToDB(value: List<String>): Any {
        val connection = TransactionManager.current().connection.connection as java.sql.Connection
        return connection.createArrayOf("text", value.toTypedArray())
    }
}

internal fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, TextArrayColumnType())

internal object VendorsTable : Table(name = "vendors") {
    val id = uuid("id").autoGenerate()
    val email = text("email")
    val password = text("password")
    val candidates = textArray("candidates")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class PostgresVendorRepository : VendorRepository {
    private fun ResultRow.toVendor(): Vendor =
        Vendor(
            id = this[VendorsTable.id],
            email = this[VendorsTable.email],
            hashedPassword = this[VendorsTable.password],
            candidates = this[VendorsTable.candidates],
            createdAt = this[VendorsTable.createdAt],
            updatedAt = this[VendorsTable.updatedAt],
            deletedAt = this[VendorsTable.deletedAt].toOption(),
        )

    override suspend fun findByEmail(email: String): Either<DatabaseFailure, Option<Vendor>> =
        Either
            .catch {
                dbQuery {
                    VendorsTable
                        .selectAll()
                        .where { VendorsTable.email eq email }
                        .map { it.toVendor() }
                        .firstOrNull()
                        .toOption()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find vendor by email: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findAll(includeDeleted: Boolean): Either<DatabaseFailure, List<Vendor>> =
        Either
            .catch {
                dbQuery {
                    val query = VendorsTable.selectAll()
                    if (!includeDeleted) {
                        query.where { VendorsTable.deletedAt eq null }
                    }
                    query.map { it.toVendor() }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find all vendors: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun upsert(
        email: String,
        hashedPassword: String,
        candidates: Option<List<String>>,
    ): Either<DatabaseFailure, Pair<Vendor, Boolean>> =
        Either
            .catch {
                dbQuery {
                    val candidatesList = candidates.getOrElse { emptyList() }
                    val updateCandidates = candidates.isSome()
                    val conn = TransactionManager.current().connection.connection as java.sql.Connection

                    @Suppress("MaxLineLength")
                    val sql =
                        """
                        INSERT INTO vendors (email, password, candidates, created_at, updated_at)
                        VALUES (?, ?, ?, NOW(), NOW())
                        ON CONFLICT (email) DO UPDATE SET
                            password = EXCLUDED.password,
                            candidates = CASE WHEN ? THEN EXCLUDED.candidates ELSE vendors.candidates END,
                            updated_at = NOW(),
                            deleted_at = NULL
                        RETURNING *, (xmax = 0) AS is_new
                        """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, email)
                        stmt.setString(2, hashedPassword)
                        stmt.setArray(3, conn.createArrayOf("text", candidatesList.toTypedArray()))
                        stmt.setBoolean(4, updateCandidates)

                        val rs = stmt.executeQuery()
                        rs.next()

                        @Suppress("UNCHECKED_CAST")
                        val vendor =
                            Vendor(
                                id = UUID.fromString(rs.getString("id")),
                                email = rs.getString("email"),
                                hashedPassword = rs.getString("password"),
                                candidates = (rs.getArray("candidates").array as Array<String>).toList(),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                                deletedAt = rs.getTimestamp("deleted_at").toOption().map { it.toInstant() },
                            )
                        val isNew = rs.getBoolean("is_new")

                        Pair(vendor, isNew)
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to upsert vendor: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun softDelete(id: UUID): Either<DatabaseFailure, Option<Vendor>> =
        Either
            .catch {
                dbQuery {
                    val now = Instant.now()
                    val updated =
                        VendorsTable.update({
                            (VendorsTable.id eq id) and (VendorsTable.deletedAt eq null)
                        }) {
                            it[deletedAt] = now
                            it[updatedAt] = now
                        }
                    if (updated > 0) {
                        VendorsTable
                            .selectAll()
                            .where { VendorsTable.id eq id }
                            .map { it.toVendor() }
                            .firstOrNull()
                            .toOption()
                    } else {
                        none()
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to soft delete vendor: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findById(id: UUID): Either<DatabaseFailure, Option<Vendor>> =
        Either
            .catch {
                dbQuery {
                    VendorsTable
                        .selectAll()
                        .where { VendorsTable.id eq id }
                        .map { it.toVendor() }
                        .firstOrNull()
                        .toOption()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find vendor by id: ${error.message}",
                    cause = error,
                )
            }
}
