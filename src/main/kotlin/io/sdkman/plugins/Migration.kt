package io.sdkman.plugins

import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import io.sdkman.config.DatabaseConfig

fun Application.configureDatabaseMigration(config: DatabaseConfig) {
    Flyway.configure()
        .dataSource(
            "jdbc:postgresql://${config.host}:${config.port}/sdkman?sslMode=prefer&loglevel=2",
            config.username,
            config.password
        )
        .load()
        .migrate()
}
