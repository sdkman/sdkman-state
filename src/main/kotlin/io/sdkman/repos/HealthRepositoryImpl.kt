package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.HealthRepository
import org.jetbrains.exposed.sql.transactions.TransactionManager

class HealthRepositoryImpl : HealthRepository {
    override suspend fun checkDatabaseConnection(): Either<DatabaseFailure, Unit> =
        Either
            .catch {
                dbQuery {
                    TransactionManager.current().exec("SELECT 1")
                    Unit
                }
            }.mapLeft { throwable ->
                DatabaseFailure(
                    message = "Database connection failed: ${throwable.message}",
                    cause = throwable,
                )
            }
}
