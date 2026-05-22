package io.sdkman.state.config

import arrow.core.Option
import io.ktor.server.config.*

interface AppConfig {
    val databaseHost: String
    val databasePort: Int
    val databaseName: String
    val databaseUsername: Option<String>
    val databasePassword: Option<String>
    val databasePoolMaxSize: Int
    val databasePoolMinIdle: Int
    val databasePoolConnectionTimeoutMs: Long
    val databasePoolMaxLifetimeMs: Long
    val databasePoolIdleTimeoutMs: Long
    val cacheMaxAge: Int
    val adminEmail: String
    val adminPassword: String
    val jwtSecret: String
    val jwtExpiry: Int
    val semverishCandidates: Set<String>
}

class DefaultAppConfig(
    private val config: ApplicationConfig,
) : AppConfig {
    override val databaseHost: String = config.getStringOrDefault("database.host", "localhost")
    override val databasePort: Int = config.getIntOrDefault("database.port", 5432)
    override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")
    override val databaseUsername: Option<String> = config.getOptionString("database.username")
    override val databasePassword: Option<String> = config.getOptionString("database.password")
    override val databasePoolMaxSize: Int = config.getIntOrDefault("database.pool.maxSize", 20)
    override val databasePoolMinIdle: Int = config.getIntOrDefault("database.pool.minIdle", 2)
    override val databasePoolConnectionTimeoutMs: Long =
        config.getLongOrDefault("database.pool.connectionTimeoutMs", 5_000L)
    override val databasePoolMaxLifetimeMs: Long =
        config.getLongOrDefault("database.pool.maxLifetimeMs", 1_800_000L)
    override val databasePoolIdleTimeoutMs: Long =
        config.getLongOrDefault("database.pool.idleTimeoutMs", 600_000L)
    override val cacheMaxAge: Int = config.getIntOrDefault("api.cache.control", 600)
    override val adminEmail: String = config.getStringOrDefault("admin.email", "admin@sdkman.io")
    override val adminPassword: String = config.getStringOrDefault("admin.password", "changeme")
    override val jwtSecret: String
        get() = config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.getIntOrDefault("jwt.expiry", 10)
    override val semverishCandidates: Set<String> =
        config.getCommaSeparatedSetOrDefault("validation.semverish.candidates", emptySet())
}
