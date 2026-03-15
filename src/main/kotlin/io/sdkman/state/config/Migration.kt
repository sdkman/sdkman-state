package io.sdkman.state.config

import arrow.core.getOrElse
import io.ktor.server.application.*
import org.flywaydb.core.Flyway

fun Application.configureDatabaseMigration(config: AppConfig) {
    Flyway
        .configure()
        .dataSource(
            config.jdbcUrl,
            config.databaseUsername.getOrElse { "" },
            config.databasePassword.getOrElse { "" },
        ).load()
        .migrate()
}
