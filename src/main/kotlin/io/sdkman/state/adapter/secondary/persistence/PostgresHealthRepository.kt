package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.HealthCheckSuccess
import io.sdkman.state.domain.repository.HealthRepository
import org.jetbrains.exposed.sql.transactions.TransactionManager

class PostgresHealthRepository : HealthRepository {
    override suspend fun checkDatabaseConnection(): Either<DatabaseFailure, HealthCheckSuccess> =
        Either
            .catch {
                dbQuery {
                    TransactionManager.current().exec("SELECT 1")
                    HealthCheckSuccess
                }
            }.mapLeft { throwable ->
                DatabaseFailure.ConnectionFailure(
                    message = "Database connection failed: ${throwable.message}",
                    cause = throwable,
                )
            }
}
