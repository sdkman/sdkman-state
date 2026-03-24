package io.sdkman.state.domain.model

data class VendorWithPassword(
    val vendor: Vendor,
    val plaintextPassword: String,
)
