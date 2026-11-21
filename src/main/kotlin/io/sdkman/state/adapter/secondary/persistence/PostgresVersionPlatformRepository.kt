package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.repository.VersionPlatformRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

class PostgresVersionPlatformRepository : VersionPlatformRepository {
    override suspend fun findPlatforms(
        candidate: String,
        version: String,
        distribution: Option<Distribution>,
    ): Either<DatabaseFailure, List<Platform>> =
        Either
            .catch {
                dbQuery {
                    VersionsTable
                        .select(VersionsTable.platform)
                        .where {
                            (VersionsTable.candidate eq candidate) and
                                (VersionsTable.version eq version) and
                                versionsDistributionEq(distribution)
                        }.map { Platform.valueOf(it[VersionsTable.platform]) }
                }
            }.mapLeft { error ->
                DatabaseFailure.QueryExecutionFailure(
                    message = "Failed to find version platforms: ${error.message}",
                    cause = error,
                )
            }
}
