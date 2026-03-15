package io.sdkman.state.config

import arrow.core.Option
import arrow.core.toOption
import io.ktor.server.config.*

fun ApplicationConfig.getStringOrDefault(
    path: String,
    default: String,
): String = propertyOrNull(path)?.getString() ?: default

fun ApplicationConfig.getIntOrDefault(
    path: String,
    default: Int,
): Int = propertyOrNull(path)?.getString()?.toIntOrNull() ?: default

fun ApplicationConfig.getOptionString(path: String): Option<String> = propertyOrNull(path)?.getString().toOption()

val AppConfig.jdbcUrl: String
    get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslMode=prefer&loglevel=2"
