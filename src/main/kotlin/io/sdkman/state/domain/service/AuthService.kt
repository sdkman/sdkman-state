package io.sdkman.state.domain.service

import arrow.core.Either
import io.sdkman.state.domain.error.AuthError

interface AuthService {
    suspend fun login(
        email: String,
        password: String,
        clientIp: String,
    ): Either<AuthError, String>
}
