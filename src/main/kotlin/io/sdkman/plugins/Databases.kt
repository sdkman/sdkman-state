package io.sdkman.plugins

import io.ktor.server.application.*
import io.sdkman.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabase(config: DatabaseConfig) =
    Database.connect(
        url = "jdbc:postgresql://${config.host}:${config.port}/sdkman?sslMode=prefer&loglevel=2",
        user = config.username,
        password = config.password,
        driver = "org.postgresql.Driver",
    )
