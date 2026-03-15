package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.HealthCheckSuccess
import io.sdkman.domain.HealthRepository
import org.jetbrains.exposed.sql.transactions.TransactionManager

class HealthRepositoryImpl : HealthRepository {
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
