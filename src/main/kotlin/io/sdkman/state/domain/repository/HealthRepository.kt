package io.sdkman.state.domain.repository

import arrow.core.Either
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.HealthCheckSuccess

interface HealthRepository {
    suspend fun checkDatabaseConnection(): Either<DatabaseFailure, HealthCheckSuccess>
}
