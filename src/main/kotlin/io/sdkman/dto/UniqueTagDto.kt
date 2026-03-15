@file:UseSerializers(OptionSerializer::class)

package io.sdkman.dto

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class UniqueTagDto(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = None,
    val platform: Platform,
)

fun UniqueTagDto.toDomain(): UniqueTag =
    UniqueTag(
        candidate = candidate,
        tag = tag,
        distribution = distribution,
        platform = platform,
    )

fun UniqueTag.toDto(): UniqueTagDto =
    UniqueTagDto(
        candidate = candidate,
        tag = tag,
        distribution = distribution,
        platform = platform,
    )
