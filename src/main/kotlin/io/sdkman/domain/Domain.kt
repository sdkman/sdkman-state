package io.sdkman.domain

import kotlinx.serialization.Serializable

@Serializable
data class CandidateVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
    val url: String,
    val visible: Boolean,
    val md5sum: String?,
    val sha256sum: String?,
    val sha512sum: String?,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
)

