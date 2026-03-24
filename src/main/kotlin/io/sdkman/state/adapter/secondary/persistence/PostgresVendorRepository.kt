package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.toOption
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.repository.VendorRepository
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

internal object VendorsTable : Table(name = "vendors") {
    val id = uuid("id")
    val email = text("email")
    val password = text("password")
    val candidates = array<String>("candidates")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class PostgresVendorRepository : VendorRepository {
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

    override suspend fun upsert(vendor: Vendor): Either<DatabaseFailure, Vendor> =
        Either
            .catch {
                dbQuery {
                    val existing =
                        VendorsTable
                            .selectAll()
                            .where { VendorsTable.email eq vendor.email }
                            .firstOrNull()

                    if (existing != null) {
                        VendorsTable.update({ VendorsTable.email eq vendor.email }) {
                            it[password] = vendor.password
                            it[candidates] = vendor.candidates
                            it[updatedAt] = vendor.updatedAt
                            it[deletedAt] = vendor.deletedAt.getOrNull()
                        }
                        VendorsTable
                            .selectAll()
                            .where { VendorsTable.email eq vendor.email }
                            .first()
                            .toVendor()
                    } else {
                        VendorsTable.insert {
                            it[id] = vendor.id
                            it[email] = vendor.email
                            it[password] = vendor.password
                            it[candidates] = vendor.candidates
                            it[createdAt] = vendor.createdAt
                            it[updatedAt] = vendor.updatedAt
                            it[deletedAt] = vendor.deletedAt.getOrNull()
                        }
                        vendor
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
                    val existing =
                        VendorsTable
                            .selectAll()
                            .where { VendorsTable.id eq id }
                            .firstOrNull()
                            ?.toVendor()

                    when {
                        existing == null -> None
                        existing.deletedAt.isSome() -> None
                        else -> {
                            val now = Instant.now()
                            VendorsTable.update({ VendorsTable.id eq id }) {
                                it[deletedAt] = now
                                it[updatedAt] = now
                            }
                            Some(existing.copy(deletedAt = Some(now), updatedAt = now))
                        }
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to soft delete vendor: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findAll(): Either<DatabaseFailure, List<Vendor>> =
        Either
            .catch {
                dbQuery {
                    VendorsTable
                        .selectAll()
                        .map { it.toVendor() }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find all vendors: ${error.message}",
                    cause = error,
                )
            }

    private fun org.jetbrains.exposed.sql.ResultRow.toVendor(): Vendor =
        Vendor(
            id = this[VendorsTable.id],
            email = this[VendorsTable.email],
            password = this[VendorsTable.password],
            candidates = this[VendorsTable.candidates],
            createdAt = this[VendorsTable.createdAt],
            updatedAt = this[VendorsTable.updatedAt],
            deletedAt = this[VendorsTable.deletedAt].toOption(),
        )
}
