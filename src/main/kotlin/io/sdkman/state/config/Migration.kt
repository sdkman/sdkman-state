package io.sdkman.state.config

import io.ktor.server.application.*
import org.flywaydb.core.Flyway

fun Application.configureDatabaseMigration(config: DatabaseConfig) {
    Flyway
        .configure()
        .dataSource(
            config.jdbcUrl,
            config.username,
            config.password,
        ).load()
        .migrate()
}
