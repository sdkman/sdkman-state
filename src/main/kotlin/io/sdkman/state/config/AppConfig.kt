package io.sdkman.state.config

import arrow.core.Option
import io.ktor.server.config.*

interface AppConfig {
    val databaseHost: String
    val databasePort: Int
    val databaseName: String
    val databaseUsername: Option<String>
    val databasePassword: Option<String>
    val authUsername: String
    val authPassword: String
    val cacheMaxAge: Int
    val adminEmail: String
    val adminPassword: String
    val jwtSecret: String
    val jwtExpiry: Int
}

class DefaultAppConfig(
    private val config: ApplicationConfig,
) : AppConfig {
    override val databaseHost: String = config.getStringOrDefault("database.host", "localhost")
    override val databasePort: Int = config.getIntOrDefault("database.port", 5432)
    override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")
    override val databaseUsername: Option<String> = config.getOptionString("database.username")
    override val databasePassword: Option<String> = config.getOptionString("database.password")
    override val authUsername: String = config.getStringOrDefault("api.username", "")
    override val authPassword: String = config.getStringOrDefault("api.password", "")
    override val cacheMaxAge: Int = config.getIntOrDefault("api.cache.control", 600)
    override val adminEmail: String = config.getStringOrDefault("admin.email", "admin@sdkman.io")
    override val adminPassword: String = config.getStringOrDefault("admin.password", "changeme")
    override val jwtSecret: String
        get() = config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.getIntOrDefault("jwt.expiry", 10)
}
