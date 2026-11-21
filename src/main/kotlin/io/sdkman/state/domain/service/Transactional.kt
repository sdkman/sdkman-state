package io.sdkman.state.domain.service

import arrow.core.Either

/**
 * Application-level port that runs a block inside a single database transaction.
 *
 * `POST /versions` writes the version and its tag assignments in one transaction, so a failed tag
 * assignment rolls back the version write. Defined here (in the domain) so application services do
 * not import Exposed directly — the infrastructure adapter lives in `adapter.secondary.persistence`.
 *
 * Contract: if [block] returns `Either.Left`, the implementation MUST roll back. If it returns
 * `Either.Right`, the implementation MUST commit. Exceptions thrown by [block] also roll back.
 */
interface Transactional {
    suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A>
}
