package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.DomainError
import io.sdkman.state.domain.model.TokenResponse
import io.sdkman.state.domain.model.Vendor
import io.sdkman.state.domain.model.VendorWithPassword
import java.util.UUID

interface AuthService {
    suspend fun authenticate(
        email: String,
        password: String,
    ): Either<DomainError, TokenResponse>

    suspend fun createOrUpdateVendor(
        email: String,
        candidates: List<String>,
    ): Either<DomainError, VendorWithPassword>

    suspend fun softDeleteVendor(id: UUID): Either<DomainError, Vendor>

    suspend fun listVendors(): Either<DomainError, List<Vendor>>
}
