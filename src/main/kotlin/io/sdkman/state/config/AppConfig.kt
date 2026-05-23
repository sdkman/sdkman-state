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
    override val databaseHost: String = config.property("database.host").getString()
    override val databasePort: Int = config.property("database.port").getString().toInt()
    override val databaseName: String = config.property("database.name").getString()
    override val databaseUsername: Option<String> = config.getOptionString("database.username")
    override val databasePassword: Option<String> = config.getOptionString("database.password")
    override val databasePoolMaxSize: Int = config.property("database.pool.maxSize").getString().toInt()
    override val databasePoolMinIdle: Int = config.property("database.pool.minIdle").getString().toInt()
    override val databasePoolConnectionTimeoutMs: Long =
        config.property("database.pool.connectionTimeoutMs").getString().toLong()
    override val databasePoolMaxLifetimeMs: Long =
        config.property("database.pool.maxLifetimeMs").getString().toLong()
    override val databasePoolIdleTimeoutMs: Long =
        config.property("database.pool.idleTimeoutMs").getString().toLong()
    override val cacheMaxAge: Int = config.property("api.cache.control").getString().toInt()
    override val adminEmail: String = config.property("admin.email").getString()
    override val adminPassword: String = config.property("admin.password").getString()
    override val jwtSecret: String
        get() = config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.property("jwt.expiry").getString().toInt()
    override val semverishCandidates: Set<String> =
        config.getCommaSeparatedSet("validation.semverish.candidates")
}
