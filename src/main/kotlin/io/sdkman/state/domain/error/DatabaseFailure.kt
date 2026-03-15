package io.sdkman.state.domain.error

sealed class DatabaseFailure(
    override val message: String,
    override val cause: Throwable,
) : Throwable(message, cause) {
    data class ConnectionFailure(
        override val message: String,
        override val cause: Throwable,
    ) : DatabaseFailure(message, cause)

    data class QueryExecutionFailure(
        override val message: String,
        override val cause: Throwable,
    ) : DatabaseFailure(message, cause)
}
