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
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val lastUpdatedAt: java.time.Instant = java.time.Instant.now(),
)

@Serializable
data class UniqueTag(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = None,
    val platform: Platform,
) : Auditable
