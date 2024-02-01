package io.sdkman.domain

import kotlinx.serialization.Serializable

@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
    val url: String,
    val visible: Boolean,
    val md5sum: String? = null,
    val sha256sum: String? = null,
    val sha512sum: String? = null,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
)

