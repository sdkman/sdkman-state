package io.sdkman.domain

import arrow.core.Either

interface HealthRepository {
    suspend fun checkDatabaseConnection(): Either<DatabaseFailure, HealthCheckSuccess>
}
