@file:UseSerializers(OptionSerializer::class)

package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.serialization.OptionSerializer
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.Version
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
internal data class AuditVersionData(
    val candidate: String,
    val version: String,
    val platform: Platform,
    val url: String,
    val visible: Option<Boolean> = none(),
    val distribution: Option<Distribution> = none(),
    val md5sum: Option<String> = none(),
    val sha256sum: Option<String> = none(),
    val sha512sum: Option<String> = none(),
    val tags: Option<List<String>> = none(),
)

@Serializable
internal data class AuditTagData(
    val candidate: String,
    val tag: String,
    val distribution: Option<Distribution> = none(),
    val platform: Platform,
)

internal fun Version.toAuditData(): AuditVersionData =
    AuditVersionData(
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

internal fun UniqueTag.toAuditData(): AuditTagData =
    AuditTagData(
        candidate = candidate,
        tag = tag,
        distribution = distribution,
        platform = platform,
    )

internal fun AuditVersionData.toDomain(): Version =
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
