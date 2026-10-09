package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Either
import io.sdkman.state.domain.service.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class ExposedTransactional : Transactional {
    override suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A> =
        withContext(Dispatchers.IO) {
            suspendTransaction {
                block().also { result -> result.onLeft { rollback() } }
            }
        }
}
