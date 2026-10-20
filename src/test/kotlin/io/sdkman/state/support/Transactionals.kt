package io.sdkman.state.support

import arrow.core.Either
import io.sdkman.state.domain.service.Transactional

/**
 * A [Transactional] that runs its block directly, so service unit specs need no registered Exposed
 * `Database`. Production uses `ExposedTransactional`.
 */
fun passthroughTransactional(): Transactional =
    object : Transactional {
        override suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A> = block()
    }
