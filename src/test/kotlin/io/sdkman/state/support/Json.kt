package io.sdkman.state.support

import arrow.core.getOrElse
import arrow.core.toOption
import io.sdkman.state.adapter.primary.rest.dto.UniqueTagDto
import io.sdkman.state.adapter.primary.rest.dto.UniqueVersionDto
import io.sdkman.state.adapter.primary.rest.dto.VersionDto
import io.sdkman.state.adapter.primary.rest.dto.toDto
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

fun Version.toJson() = Json.encodeToJsonElement<VersionDto>(this.toDto())

fun Version.toJsonString() = this.toJson().toString()

fun UniqueVersion.toJsonString() = Json.encodeToJsonElement(UniqueVersionDto(candidate, version, distribution, platform)).toString()

fun UniqueTag.toJsonString() = Json.encodeToJsonElement(UniqueTagDto(candidate, tag, distribution, platform)).toString()

fun String.parseJsonObject(): JsonObject = Json.decodeFromString<JsonObject>(this)

fun String.extractTags(): List<String> =
    parseJsonObject()["tags"]
        .toOption()
        .map { it.jsonArray.map { element -> element.jsonPrimitive.content } }
        .getOrElse { emptyList() }
