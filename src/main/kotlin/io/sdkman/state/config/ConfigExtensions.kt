package io.sdkman.state.config

import arrow.core.Option
import arrow.core.toOption
import io.ktor.server.config.*

fun ApplicationConfig.getOptionString(path: String): Option<String> = propertyOrNull(path).toOption().map { it.getString() }

fun ApplicationConfig.getCommaSeparatedSet(path: String): Set<String> =
    property(path)
        .getString()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

val AppConfig.jdbcUrl: String
    get() = "jdbc:postgresql://$databaseHost:$databasePort/$databaseName?sslmode=prefer"
