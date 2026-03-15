package io.sdkman.state.domain.model

import arrow.core.firstOrNone
import arrow.core.getOrElse

enum class Platform(
    val platformId: String,
) {
    LINUX_X32("linuxx32"),
    LINUX_X64("linuxx64"),
    LINUX_ARM32HF("linuxarm32hf"),
    LINUX_ARM32SF("linuxarm32sf"),
    LINUX_ARM64("linuxarm64"),
    MAC_X64("darwinx64"),
    MAC_ARM64("darwinarm64"),
    WINDOWS_X64("windowsx64"),
    UNIVERSAL("universal"),
    ;

    companion object {
        fun findByPlatformId(platformId: String): Platform =
            Platform.entries.firstOrNone { it.platformId == platformId }.getOrElse { UNIVERSAL }
    }
}
