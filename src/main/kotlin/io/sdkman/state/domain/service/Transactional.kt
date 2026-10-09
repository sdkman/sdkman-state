package io.sdkman.state.domain.service

import arrow.core.Either

interface Transactional {
    suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A>
}
