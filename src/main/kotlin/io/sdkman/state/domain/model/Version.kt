package io.sdkman.state.domain.model

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.none

data class Version(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = none(),
    val distribution: Option<Distribution> = none(),
    val md5sum: Option<String> = none(),
    val sha256sum: Option<String> = none(),
    val sha512sum: Option<String> = none(),
    val tags: Option<List<String>> = none(),
) : Auditable {
    val isVisible: Boolean get() = visible.getOrElse { true }
}

data class UniqueVersion(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
)
