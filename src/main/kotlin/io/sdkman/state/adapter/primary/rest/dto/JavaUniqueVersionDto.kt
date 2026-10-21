@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class JavaUniqueVersionDto(
    val version: String,
    val platform: Platform,
    val distribution: Option<Distribution> = none(),
)

fun JavaUniqueVersionDto.toDomain(): UniqueVersion =
    UniqueVersion(
        candidate = JAVA_CANDIDATE,
        version = version,
        distribution = distribution,
        platform = platform,
    )
