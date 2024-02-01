package io.sdkman.support

import io.sdkman.domain.UniqueVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

fun CandidateVersion.toJson() = Json.encodeToJsonElement<CandidateVersion>(this)

fun CandidateVersion.toJsonString() = this.toJson().toString()

fun UniqueVersion.toJsonString() = Json.encodeToJsonElement(this).toString()