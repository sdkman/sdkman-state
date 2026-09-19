package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.firstOrNone
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateDefault
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.CandidateRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

internal object CandidatesTable : Table(name = "candidates") {
    val candidate = text("candidate")
    val name = text("name")
    val description = text("description")
    val websiteUrl = text("website_url")
    val createdAt = timestamp("created_at")
    val lastUpdatedAt = timestamp("last_updated_at")

    override val primaryKey = PrimaryKey(candidate)
}

// R5: `default` resolves over UNIVERSAL and LINUX_X64 alone. The list is a restriction, not a
// preference order — the preference itself lives in the service, which picks between the rows
// this returns.
private val DEFAULT_PLATFORMS = listOf(Platform.UNIVERSAL.name, Platform.LINUX_X64.name)

private const val LTS_TAG = "lts"

class PostgresCandidateRepository : CandidateRepository {
    private fun ResultRow.toCandidate(): Candidate =
        Candidate(
            candidate = this[CandidatesTable.candidate],
            name = this[CandidatesTable.name],
            description = this[CandidatesTable.description],
            websiteUrl = this[CandidatesTable.websiteUrl],
            createdAt = this[CandidatesTable.createdAt],
            lastUpdatedAt = this[CandidatesTable.lastUpdatedAt],
        )

    // R11: ascending by identifier, matching the sort the Mongo repository applied. The order is
    // part of the `GET /candidates` contract, so it is fixed here rather than left to the planner.
    override suspend fun findAll(): Either<DatabaseFailure, List<Candidate>> =
        Either
            .catch {
                dbQuery {
                    CandidatesTable
                        .selectAll()
                        .orderBy(CandidatesTable.candidate to SortOrder.ASC)
                        .map { it.toCandidate() }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find all candidates: ${error.message}",
                    cause = error,
                )
            }

    // R6: the distribution filter reads `versions.distribution`, not `version_tags.distribution`.
    // `findByTag` behind GET /versions/{c}/tags/lts reads the same column, and the two endpoints
    // must not disagree about the same candidate.
    // R7: no `visible` filter, for the same reason — `findByTag` does not filter on it either.
    override suspend fun findDefaults(): Either<DatabaseFailure, List<CandidateDefault>> =
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
                        }.map {
                            CandidateDefault(
                                candidate = it[VersionTagsTable.candidate],
                                platform = Platform.valueOf(it[VersionTagsTable.platform]),
                                version = it[VersionsTable.version],
                            )
                        }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find candidate defaults: ${error.message}",
                    cause = error,
                )
            }

    // R2: registration is an upsert, and the identifier is never mutated -- `candidate` is the
    // conflict target and is absent from the SET clause, so a second POST rewrites the metadata
    // and refreshes `last_updated_at` alone.
    //
    // `RETURNING *, (xmax = 0) AS is_new` is the idiom PostgresVendorRepository already uses:
    // Postgres leaves `xmax` at zero on an inserted tuple and non-zero on one the DO UPDATE
    // touched, so the created/updated answer comes back from the same statement. Reading it with
    // a prior SELECT would reintroduce the race this single statement closes, which is what the
    // concurrent double-post acceptance test pins.
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

                        val candidate =
                            Candidate(
                                candidate = rs.getString("candidate"),
                                name = rs.getString("name"),
                                description = rs.getString("description"),
                                websiteUrl = rs.getString("website_url"),
                                createdAt = rs.getTimestamp("created_at").toInstant(),
                                lastUpdatedAt = rs.getTimestamp("last_updated_at").toInstant(),
                            )
                        val isNew = rs.getBoolean("is_new")

                        Pair(candidate, isNew)
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to upsert candidate: ${error.message}",
                    cause = error,
                )
            }

    // R3: the version count *is* the mechanism behind the 409, not a friendlier surface over a
    // foreign key -- there is none (docs/decisions/0008). It is counted before the row is read,
    // so a candidate that still owns versions reports HasVersions whether or not it is registered.
    // It is a check and not a lock: a publish arriving after the count is not stopped by it, which
    // the spec accepts because the publish path never consults this table in this part.
    override suspend fun delete(candidate: String): Either<DatabaseFailure, CandidateDeletion> =
        Either
            .catch {
                dbQuery {
                    val versionCount =
                        VersionsTable
                            .selectAll()
                            .where { VersionsTable.candidate eq candidate }
                            .count()

                    if (versionCount > 0) {
                        CandidateDeletion.HasVersions(versionCount)
                    } else {
                        CandidatesTable
                            .selectAll()
                            .where { CandidatesTable.candidate eq candidate }
                            .map { it.toCandidate() }
                            .firstOrNone()
                            .fold(
                                { CandidateDeletion.NotFound },
                                { existing ->
                                    CandidatesTable.deleteWhere { CandidatesTable.candidate eq candidate }
                                    CandidateDeletion.Deleted(existing)
                                },
                            )
                    }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to delete candidate: ${error.message}",
                    cause = error,
                )
            }
}
