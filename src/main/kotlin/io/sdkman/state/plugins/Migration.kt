package io.sdkman.state.plugins

import io.ktor.server.application.*
import io.sdkman.state.config.DatabaseConfig
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
