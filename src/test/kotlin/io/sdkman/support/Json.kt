package io.sdkman.support

import arrow.core.getOrElse
import arrow.core.toOption
import io.sdkman.domain.UniqueVersion
import io.sdkman.domain.Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

fun Version.toJson() = Json.encodeToJsonElement<Version>(this)

fun Version.toJsonString() = this.toJson().toString()

fun UniqueVersion.toJsonString() = Json.encodeToJsonElement(this).toString()

fun String.parseJsonObject(): JsonObject = Json.decodeFromString<JsonObject>(this)

fun String.extractTags(): List<String> =
    parseJsonObject()["tags"]
        .toOption()
        .map { it.jsonArray.map { element -> element.jsonPrimitive.content } }
        .getOrElse { emptyList() }
