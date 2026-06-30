@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.primary.rest.dto

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class TagAssignmentDto(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val distribution: Option<Distribution> = none(),
    val tag: String,
)

fun TagAssignmentDto.toDomain(): TagAssignment =
    TagAssignment(
        candidate = candidate,
        version = version,
        distribution = distribution,
        platform = platform,
        tag = tag,
    )
