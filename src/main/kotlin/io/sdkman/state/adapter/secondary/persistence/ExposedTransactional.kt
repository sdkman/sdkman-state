package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import io.sdkman.state.domain.service.Transactional
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Exposed-backed implementation of the [Transactional] port. Wraps the block in a single
 * `newSuspendedTransaction(Dispatchers.IO)` so nested `dbQuery {}` calls inside [block] reuse the
 * same JDBC connection (Exposed's default behaviour for nested suspended transactions). On a
 * `Left` result the explicit `rollback()` flips Exposed's `isRolledBack` flag and rolls back the
 * underlying JDBC connection so the outer transaction does not commit partial state.
 */
class ExposedTransactional : Transactional {
    override suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A> =
        newSuspendedTransaction(Dispatchers.IO) {
            block().also { result -> result.onLeft { rollback() } }
        }
}
