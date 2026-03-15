package io.sdkman.state.config

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabase(config: DatabaseConfig) =
    Database.connect(
        url = config.jdbcUrl,
        user = config.username,
        password = config.password,
        driver = "org.postgresql.Driver",
    )
