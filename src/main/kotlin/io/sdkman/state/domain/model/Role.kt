package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.firstOrNone

enum class Role(
    val claim: String,
) {
    ADMIN("admin"),
    VENDOR("vendor"),
    COMMUNITY("community"),
    ;

    companion object {
        fun fromClaim(claim: String): Option<Role> = entries.firstOrNone { it.claim == claim }
    }
}
