package io.sdkman.state.config

import arrow.core.Option
import arrow.core.raise.option
import io.ktor.server.config.*
import io.sdkman.state.domain.model.CommunityAccount

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
    val communityAccount: Option<CommunityAccount>
    val jwtSecret: String
    val jwtExpiry: Int
    val semverishCandidates: Set<String>
    val rateLimitEnabled: Boolean
    val candidateRefreshIntervalMs: Long
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
    override val communityAccount: Option<CommunityAccount> =
        option {
            CommunityAccount(
                email = config.getOptionNonBlankString("community.email").bind(),
                password = config.getOptionNonBlankString("community.password").bind(),
            )
        }
    override val jwtSecret: String
        get() = config.property("jwt.secret").getString()
    override val jwtExpiry: Int = config.property("jwt.expiry").getString().toInt()
    override val semverishCandidates: Set<String> =
        config.getCommaSeparatedSet("validation.semverish.candidates")
    override val rateLimitEnabled: Boolean =
        config.property("auth.rateLimit.enabled").getString().toBooleanStrict()
    override val candidateRefreshIntervalMs: Long =
        config.property("candidates.refresh.intervalMs").getString().toLong()
}
