@file:UseSerializers(OptionSerializer::class)

package io.sdkman.domain

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
    val url: String,
    val visible: Boolean,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
)

