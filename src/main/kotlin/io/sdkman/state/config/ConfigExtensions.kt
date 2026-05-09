package io.sdkman.state.config

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.toOption
import io.ktor.server.config.*

fun ApplicationConfig.getStringOrDefault(
    path: String,
    default: String,
): String = propertyOrNull(path).toOption().map { it.getString() }.getOrElse { default }

fun ApplicationConfig.getIntOrDefault(
    path: String,
    default: Int,
): Int = propertyOrNull(path).toOption().map { it.getString().toInt() }.getOrElse { default }

fun ApplicationConfig.getOptionString(path: String): Option<String> = propertyOrNull(path).toOption().map { it.getString() }

fun ApplicationConfig.getCommaSeparatedSetOrDefault(
    path: String,
    default: Set<String>,
): Set<String> =
    propertyOrNull(path)
        .toOption()
        .map { property ->
            property
                .getString()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrElse { default }

val AppConfig.jdbcUrl: String
    get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslMode=prefer&loglevel=2"
