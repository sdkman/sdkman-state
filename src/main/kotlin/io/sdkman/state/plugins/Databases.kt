package io.sdkman.state.plugins

import io.ktor.server.application.*
import io.sdkman.state.config.DatabaseConfig
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabase(config: DatabaseConfig) =
    Database.connect(
        url = config.jdbcUrl,
        user = config.username,
        password = config.password,
        driver = "org.postgresql.Driver",
    )
