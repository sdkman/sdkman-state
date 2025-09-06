package io.sdkman.repos

import arrow.core.Either
import io.sdkman.domain.DatabaseFailure
import io.sdkman.domain.HealthRepository
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class HealthRepositoryImpl : HealthRepository {

    //TODO: Don't use versions table! Use SELECT 1 query using the Exposed SQL strings:
    // (https://www.jetbrains.com/help/exposed/working-with-sql-strings.html)
    private object HealthCheck : IntIdTable(name = "versions") {
        // We'll use the existing versions table to check connectivity
    }

    private suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    override suspend fun checkDatabaseConnection(): Either<DatabaseFailure, Unit> = Either.catch {
        dbQuery {
            HealthCheck.selectAll().limit(1).count()
            Unit
        }
    }.mapLeft { throwable ->
        DatabaseFailure(
            message = "Database connection failed: ${throwable.message}",
            cause = throwable
        )
    }
}