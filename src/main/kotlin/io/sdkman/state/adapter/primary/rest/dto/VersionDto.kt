@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class VersionDto(
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
)

fun Version.toDto(): VersionDto =
    VersionDto(
        candidate = candidate,
        version = version,
        platform = platform,
        url = url,
        visible = visible,
        distribution = distribution,
        md5sum = md5sum,
        sha256sum = sha256sum,
        sha512sum = sha512sum,
        tags = tags,
    )

fun VersionDto.toDomain(): Version =
    Version(
        candidate = candidate,
        version = version,
        platform = platform,
        url = url,
        visible = visible,
        distribution = distribution,
        md5sum = md5sum,
        sha256sum = sha256sum,
        sha512sum = sha512sum,
        tags = tags,
    )
