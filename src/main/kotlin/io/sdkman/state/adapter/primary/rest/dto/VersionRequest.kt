@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class VersionRequest(
    val candidate: Option<String> = none(),
    val version: Option<String> = none(),
    val platform: Option<String> = none(),
    val url: Option<String> = none(),
    val visible: Option<Boolean> = none(),
    val distribution: Option<String> = none(),
    val md5sum: Option<String> = none(),
    val sha256sum: Option<String> = none(),
    val sha512sum: Option<String> = none(),
    val tags: Option<List<String>> = none(),
)
