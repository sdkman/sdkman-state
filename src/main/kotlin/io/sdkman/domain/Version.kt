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
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = None,
    val distribution: Option<Distribution> = None,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
    val tags: Option<List<String>> = None,
) : Auditable

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
)
