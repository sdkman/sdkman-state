package io.sdkman.support

import arrow.core.getOrElse
import arrow.core.toOption
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import io.sdkman.dto.UniqueTagDto
import io.sdkman.dto.UniqueVersionDto
import io.sdkman.dto.VersionDto
import io.sdkman.dto.toDto
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
