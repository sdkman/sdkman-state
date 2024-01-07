package io.sdkman.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

fun CandidateVersion.toJson() = Json.encodeToJsonElement(this)

fun CandidateVersion.toJsonString() = this.toJson().toString()