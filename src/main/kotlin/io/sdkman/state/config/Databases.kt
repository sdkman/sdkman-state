package io.sdkman.state.config

import arrow.core.getOrElse
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabase(config: AppConfig) =
    Database.connect(
        url = config.jdbcUrl,
        user = config.databaseUsername.getOrElse { "" },
        password = config.databasePassword.getOrElse { "" },
        driver = "org.postgresql.Driver",
    )
