@file:UseSerializers(OptionSerializer::class)

package io.sdkman.dto

import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class UniqueVersionDto(
    val candidate: String,
    val version: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
)

fun UniqueVersionDto.toDomain(): UniqueVersion =
    UniqueVersion(
        candidate = candidate,
        version = version,
        distribution = distribution,
        platform = platform,
    )
