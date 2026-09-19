package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.none
import arrow.core.some
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateRegistration
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

        // Order is the resolution order, not merely a filter: `UNIVERSAL` wins over
        // `LINUX_X64` and no other platform is consulted at all (business rule 5).
        val DEFAULT_PLATFORMS = listOf("UNIVERSAL", "LINUX_X64")
    }

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

    // Ordering is ascending by identifier (business rule 11), matching the sort the
    // Mongo repository applied, so the listing stays stable across the cutover.
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

    // `(xmax = 0)` tells an insert from an update without a preceding SELECT, so two
    // concurrent registrations of one new candidate still yield exactly one 201.
    // The identifier is never updated on conflict (business rule 2).
    override suspend fun upsert(registration: CandidateRegistration): Either<DatabaseFailure, Pair<Candidate, Boolean>> =
        Either
            .catch {
                dbQuery {
                    val conn = TransactionManager.current().connection.connection as java.sql.Connection

                    val sql =
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

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, registration.candidate)
                        stmt.setString(2, registration.name)
                        stmt.setString(3, registration.description)
                        stmt.setString(4, registration.websiteUrl)

                        val rs = stmt.executeQuery()
                        rs.next()

                        Pair(rs.toCandidate(), rs.getBoolean("is_new"))
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to upsert candidate: ${error.message}",
                    cause = error,
                )
            }

    // Deleting and reading the removed row in one statement keeps the caller from
    // racing a concurrent delete between a SELECT and a DELETE.
    override suspend fun delete(candidate: String): Either<DatabaseFailure, Option<Candidate>> =
        Either
            .catch {
                dbQuery {
                    val conn = TransactionManager.current().connection.connection as java.sql.Connection

                    val sql = "DELETE FROM candidates WHERE candidate = ? RETURNING *"

                    conn.prepareStatement(sql).use { stmt ->
                        stmt.setString(1, candidate)

                        val rs = stmt.executeQuery()
                        if (rs.next()) rs.toCandidate().some() else none()
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete candidate: ${error.message}",
                    cause = error,
                )
            }

    // Counts every version under the candidate, visible or not: the delete guard asks whether
    // anything was ever published, not whether anything is currently listed (business rule 3).
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

    // One query for the whole registry, so listing candidates never fans out into a query per
    // candidate. The filters mirror `PostgresVersionRepository.findByTag` exactly: the tag's
    // candidate and platform, and the *version's* distribution (business rule 6). Reading
    // `version_tags.distribution` here instead would make `GET /candidates` and
    // `GET /versions/{c}/tags/lts` disagree about the same candidate. `visible` is deliberately
    // not filtered, for the same reason: the two reads must agree even while a retired row still
    // holds the tag (business rule 7).
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
                                (VersionTagsTable.platform inList DEFAULT_PLATFORMS)
                        }.groupBy { it[VersionTagsTable.candidate] }
                        .mapValues { (_, tagged) ->
                            tagged
                                .sortedBy { DEFAULT_PLATFORMS.indexOf(it[VersionTagsTable.platform]) }
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
