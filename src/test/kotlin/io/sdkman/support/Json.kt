package io.sdkman.support

import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

fun Version.toJson() = Json.encodeToJsonElement<Version>(this)

fun Version.toJsonString() = this.toJson().toString()

fun UniqueVersion.toJsonString() = Json.encodeToJsonElement(this).toString()
