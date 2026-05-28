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

/**
 * Reads a HOCON value as a comma-separated list of strings. Whitespace around each entry is trimmed
 * and empty entries are dropped, so `""`, `" , "` and `"  "` all collapse to `emptyList()`. The
 * comma-separated form is used (rather than HOCON's native array form) so that the standard
 * `key = ${?ENV_VAR}` substitution pattern continues to work — environment variables are strings.
 */
fun ApplicationConfig.getStringListOrDefault(
    path: String,
    default: List<String>,
): List<String> =
    propertyOrNull(path)
        .toOption()
        .map { value ->
            value
                .getString()
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
        }.getOrElse { default }

val AppConfig.jdbcUrl: String
    get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslMode=prefer&loglevel=2"
