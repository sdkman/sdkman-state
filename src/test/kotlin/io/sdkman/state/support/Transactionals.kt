package io.sdkman.state.support

import arrow.core.Either
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.repository.AuditRepository
import io.sdkman.state.domain.service.Transactional

/**
 * A [Transactional] that runs its block directly, so service unit specs need no registered Exposed
 * `Database`. Production uses `ExposedTransactional`.
 */
fun passthroughTransactional(): Transactional =
    object : Transactional {
        override suspend fun <E, A> inTransaction(block: suspend () -> Either<E, A>): Either<E, A> = block()
    }

fun transactionalFailingWithoutRunning(txFailure: DomainError): Transactional =
    mockk<Transactional>().also { transactional ->
        coEvery { transactional.inTransaction<DomainError, Any>(any()) } returns Either.Left(txFailure)
    }

fun transactionalCheckingNoAuditYet(auditRepo: AuditRepository): Transactional =
    mockk<Transactional>().also { transactional ->
        coEvery { transactional.inTransaction<DomainError, Any>(any()) } coAnswers {
            firstArg<suspend () -> Either<DomainError, Any>>()().also {
                coVerify(exactly = 0) { auditRepo.recordAudit(any(), any(), any(), any()) }
            }
        }
    }
