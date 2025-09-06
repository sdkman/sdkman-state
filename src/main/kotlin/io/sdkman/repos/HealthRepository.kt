package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.HealthRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.TransactionManager

class HealthRepositoryImpl : HealthRepository {

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun checkDatabaseConnection(): Either<DatabaseFailure, Unit> = Either.catch {
        dbQuery {
            TransactionManager.current().exec("SELECT 1")
            Unit
        }
    }.mapLeft { throwable ->
        DatabaseFailure(
            message = "Database connection failed: ${throwable.message}",
            cause = throwable
        )
    }
}