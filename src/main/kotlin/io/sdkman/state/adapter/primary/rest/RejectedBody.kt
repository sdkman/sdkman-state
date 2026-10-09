package io.sdkman.state.adapter.primary.rest

import io.sdkman.state.adapter.primary.rest.dto.ValidationFailure

sealed interface RejectedBody {
    data object RegistryUnavailable : RejectedBody

    data class Invalid(
        val failures: List<ValidationFailure>,
    ) : RejectedBody
}
