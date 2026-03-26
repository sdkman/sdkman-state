package io.sdkman.state.domain.repository

import arrow.core.Either
import arrow.core.Option
import io.sdkman.state.domain.error.DatabaseFailure
import io.sdkman.state.domain.model.Vendor
import java.util.UUID

interface VendorRepository {
    suspend fun findByEmail(email: String): Either<DatabaseFailure, Option<Vendor>>

    suspend fun findAll(includeDeleted: Boolean): Either<DatabaseFailure, List<Vendor>>

    suspend fun upsert(
        email: String,
        hashedPassword: String,
        candidates: Option<List<String>>,
    ): Either<DatabaseFailure, Pair<Vendor, Boolean>>

    suspend fun softDelete(id: UUID): Either<DatabaseFailure, Option<Vendor>>

    suspend fun findById(id: UUID): Either<DatabaseFailure, Option<Vendor>>
}
