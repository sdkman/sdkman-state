package io.sdkman.state.domain.model

data class JavaRefusal(
    val version: Version,
    val head: String,
) : Auditable
