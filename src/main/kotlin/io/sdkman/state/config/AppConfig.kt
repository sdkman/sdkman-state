package io.sdkman.state.config

import arrow.core.Option
import io.ktor.server.config.*

interface AppConfig {
    val databaseHost: String
    val databasePort: Int
    val databaseName: String
    val databaseUsername: Option<String>
    val databasePassword: Option<String>
    val cacheMaxAge: Int
    val adminEmail: String
    val adminPassword: String
    val jwtSecret: String
    val jwtExpiry: Int

    /**
     * Candidates that opt in to strict semverish version validation on `POST /versions`.
     * Membership is checked per request; the set itself is read once at startup. See
     * `specs/semverish-version-validation.md` and the future-direction note about migrating
     * this flag onto a per-row column of a `candidates` table.
     */
    val strictSemverishCandidates: Set<String>
}

class DefaultAppConfig(
    private val config: ApplicationConfig,
) : AppConfig {
    override val databaseHost: String = config.getStringOrDefault("database.host", "localhost")
    override val databasePort: Int = config.getIntOrDefault("database.port", 5432)
    override val databaseName: String = config.getStringOrDefault("database.name", "sdkman")
    override val databaseUsername: Option<String> = config.getOptionString("database.username")
    override val databasePassword: Option<String> = config.getOptionString("database.password")
    override val cacheMaxAge: Int = config.getIntOrDefault("api.cache.control", 600)
    override val adminEmail: String = config.getStringOrDefault("admin.email", "admin@sdkman.io")
    override val adminPassword: String = config.getStringOrDefault("admin.password", "changeme")
    override val jwtSecret: String
        get() = config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.getIntOrDefault("jwt.expiry", 10)
    override val strictSemverishCandidates: Set<String> =
        config.getStringListOrDefault("validation.semverish.candidates", listOf("java")).toSet()
}
