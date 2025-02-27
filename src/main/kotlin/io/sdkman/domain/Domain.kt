@file:UseSerializers(OptionSerializer::class)

package io.sdkman.domain

import arrow.core.None
import arrow.core.Option
import arrow.core.serialization.OptionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class Version(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
    val url: String,
    val visible: Boolean,
    val md5sum: Option<String> = None,
    val sha256sum: Option<String> = None,
    val sha512sum: Option<String> = None,
)

@Serializable
data class UniqueVersion(
    val candidate: String,
    val version: String,
    val vendor: String,
    val platform: String,
)

enum class Platform(val platformId: String) {
    LINUX_X32("linuxx32"),
    LINUX_X64("linuxx64"),
    LINUX_ARM32HF("linuxarm32hf"),
    LINUX_ARM32SF("linuxarm32sf"),
    LINUX_ARM64("linuxarm64"),
    MAC_X64("darwinx64"),
    MAC_ARM64("darwinarm64"),
    WINDOWS_X64("windowsx64"),
    UNIVERSAL("universal");
}

