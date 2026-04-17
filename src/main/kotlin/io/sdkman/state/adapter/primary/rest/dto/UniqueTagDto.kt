@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class UniqueTagDto(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = none(),
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
