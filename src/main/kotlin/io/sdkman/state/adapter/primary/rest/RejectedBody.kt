package io.sdkman.state.adapter.primary.rest

import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure
import io.sdkman.state.domain.error.DatabaseFailure

sealed interface RejectedBody {
    data object RegistryUnavailable : RejectedBody

    data class Invalid(
        val failures: List<ValidationFailure>,
    ) : RejectedBody

    data class DatabaseUnavailable(
        val failure: DatabaseFailure,
    ) : RejectedBody
}
