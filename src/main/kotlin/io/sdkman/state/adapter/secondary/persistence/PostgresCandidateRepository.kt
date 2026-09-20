package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.CandidateRegistrationResult
import io.sdkman.state.domain.repository.CandidateRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet

internal object CandidatesTable : Table(name = "candidates") {
    val candidate = text("candidate")
    val name = text("name")
    val description = text("description")
    val websiteUrl = text("website_url")
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")

    override val primaryKey = PrimaryKey(candidate)
}

class PostgresCandidateRepository : CandidateRepository {
    private companion object {
        const val LTS_TAG = "lts"

        val UPSERT_SQL =
            """
            INSERT INTO candidates (candidate, name, description, website_url, created_at, last_updated_at)
            VALUES (?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (candidate) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                website_url = EXCLUDED.website_url,
                last_updated_at = NOW()
            RETURNING *, (xmax = 0) AS is_new
            """.trimIndent()

        const val DELETE_SQL = "DELETE FROM candidates WHERE candidate = ? RETURNING *"

        val PLATFORM_RESOLUTION_ORDER = listOf("UNIVERSAL", "LINUX_X64")
    }

    private fun connection(): Connection = TransactionManager.current().connection.connection as Connection

    private fun ResultRow.toCandidate(): Candidate =
        Candidate(
            candidate = this[CandidatesTable.candidate],
            name = this[CandidatesTable.name],
            description = this[CandidatesTable.description],
            websiteUrl = this[CandidatesTable.websiteUrl],
            createdAt = this[CandidatesTable.createdAt],
            lastUpdatedAt = this[CandidatesTable.lastUpdatedAt],
        )

    private fun ResultSet.toCandidate(): Candidate =
        Candidate(
            candidate = getString("candidate"),
            name = getString("name"),
            description = getString("description"),
            websiteUrl = getString("website_url"),
            createdAt = getTimestamp("created_at").toInstant(),
            lastUpdatedAt = getTimestamp("last_updated_at").toInstant(),
        )

    override suspend fun findAll(): Either<DatabaseFailure, List<Candidate>> =
        Either
            .catch {
                dbQuery {
                    CandidatesTable
                        .selectAll()
                        .orderBy(CandidatesTable.candidate)
                        .map { it.toCandidate() }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find all candidates: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun find(candidate: String): Either<DatabaseFailure, Option<Candidate>> =
        Either
            .catch {
                dbQuery {
                    CandidatesTable
                        .selectAll()
                        .where { CandidatesTable.candidate eq candidate }
                        .map { it.toCandidate() }
                        .firstOrNone()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find candidate: ${error.message}",
                    cause = error,
                )
            }

    private fun ResultSet.toRegistrationResult(): CandidateRegistrationResult =
        when {
            getBoolean("is_new") -> CandidateRegistrationResult.Registered(toCandidate())
            else -> CandidateRegistrationResult.Updated(toCandidate())
        }

    override suspend fun upsert(registration: CandidateRegistration): Either<DatabaseFailure, CandidateRegistrationResult> =
        Either
            .catch {
                dbQuery {
                    connection().prepareStatement(UPSERT_SQL).use { statement ->
                        statement.setString(1, registration.candidate)
                        statement.setString(2, registration.name)
                        statement.setString(3, registration.description)
                        statement.setString(4, registration.websiteUrl)

                        val resultSet = statement.executeQuery()
                        resultSet.next()
                        resultSet.toRegistrationResult()
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to upsert candidate: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun delete(candidate: String): Either<DatabaseFailure, Option<Candidate>> =
        Either
            .catch {
                dbQuery {
                    connection().prepareStatement(DELETE_SQL).use { statement ->
                        statement.setString(1, candidate)

                        val resultSet = statement.executeQuery()
                        if (resultSet.next()) resultSet.toCandidate().some() else none()
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete candidate: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun countVersions(candidate: String): Either<DatabaseFailure, Long> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .selectAll()
                        .where { VersionsTable.candidate eq candidate }
                        .count()
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to count versions for candidate: ${error.message}",
                    cause = error,
                )
            }

    override suspend fun findLtsDefaults(): Either<DatabaseFailure, Map<String, String>> =
        Either
            .catch {
                dbQuery {
                    VersionTagsTable
                        .join(
                            VersionsTable,
                            JoinType.INNER,
                            additionalConstraint = { VersionTagsTable.versionId eq VersionsTable.id },
                        ).select(VersionTagsTable.candidate, VersionTagsTable.platform, VersionsTable.version)
                        .where {
                            (VersionTagsTable.tag eq LTS_TAG) and
                                VersionsTable.distribution.isNull() and
                                (VersionTagsTable.platform inList PLATFORM_RESOLUTION_ORDER)
                        }.groupBy { it[VersionTagsTable.candidate] }
                        .mapValues { (_, tagged) ->
                            tagged
                                .sortedBy { PLATFORM_RESOLUTION_ORDER.indexOf(it[VersionTagsTable.platform]) }
                                .first()[VersionsTable.version]
                        }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find lts defaults: ${error.message}",
                    cause = error,
                )
            }
}
