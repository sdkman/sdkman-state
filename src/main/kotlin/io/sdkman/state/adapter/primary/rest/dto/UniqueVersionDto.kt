@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
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
