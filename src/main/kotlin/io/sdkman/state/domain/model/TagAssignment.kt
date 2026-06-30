package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.none

data class TagAssignment(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution> = none(),
    val platform: Platform,
    val tag: String,
) : Auditable
