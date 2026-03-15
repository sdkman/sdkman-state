@file:UseSerializers(OptionSerializer::class)

package io.sdkman.domain

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

data class VersionTag(
    val id: Int = 0,
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution>,
    val platform: Platform,
    val versionId: Int,
    val createdAt: kotlin.time.Instant =
        kotlin.time.Clock.System
            .now(),
    val lastUpdatedAt: kotlin.time.Instant =
        kotlin.time.Clock.System
            .now(),
)

@Serializable
data class UniqueTag(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = None,
    val platform: Platform,
) : Auditable
