package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Candidate
import io.sdkman.state.domain.model.CandidateDefault
import io.sdkman.state.domain.model.Platform
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

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

class PostgresCandidateRepository {
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
    suspend fun findAll(): Either<DatabaseFailure, List<Candidate>> =
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
    suspend fun findDefaults(): Either<DatabaseFailure, List<CandidateDefault>> =
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
}
