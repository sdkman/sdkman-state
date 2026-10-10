package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.firstOrNone

enum class Role(
    val claim: String,
) {
    ADMIN("admin"),
    VENDOR("vendor"),
    ;

    companion object {
        fun fromClaim(claim: String): Option<Role> = entries.firstOrNone { it.claim == claim }
    }
}
