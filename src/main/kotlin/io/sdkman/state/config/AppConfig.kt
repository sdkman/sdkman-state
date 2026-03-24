package io.sdkman.state.config

import arrow.core.Option
import io.ktor.server.config.*

interface AppConfig {
    val databaseHost: String
    val databasePort: Int
    val databaseName: String
    val databaseUsername: Option<String>
    val databasePassword: Option<String>
    val adminEmail: String
    val adminPassword: String
    val jwtSecret: String
    val jwtExpiry: Int
    val cacheMaxAge: Int
}

class DefaultAppConfig(
    config: ApplicationConfig,
) : AppConfig {
    override val databaseHost: String = config.getStringOrDefault("database.host", "localhost")
    override val databasePort: Int = config.getIntOrDefault("database.port", 5432)
    override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")
    override val databaseUsername: Option<String> = config.getOptionString("database.username")
    override val databasePassword: Option<String> = config.getOptionString("database.password")
    override val adminEmail: String = config.getStringOrDefault("admin.email", "admin@sdkman.io")
    override val adminPassword: String = config.getStringOrDefault("admin.password", "changeme")
    override val jwtSecret: String =
        config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.getIntOrDefault("jwt.expiry", 3)
    override val cacheMaxAge: Int = config.getIntOrDefault("api.cache.control", 600)
}
