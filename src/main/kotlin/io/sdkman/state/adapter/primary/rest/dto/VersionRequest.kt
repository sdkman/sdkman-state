@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class VersionRequest(
    val candidate: Option<String> = None,
    val version: Option<String> = None,
    val platform: Option<String> = None,
    val url: Option<String> = None,
    val visible: Option<Boolean> = None,
    val distribution: Option<String> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
    val tags: Option<List<String>> = None,
)
