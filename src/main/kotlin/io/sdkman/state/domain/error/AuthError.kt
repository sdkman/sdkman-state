package io.sdkman.state.domain.error

sealed interface AuthError {
    data object InvalidCredentials : AuthError

    data object RateLimitExceeded : AuthError

    data object TokenCreationFailed : AuthError
}
