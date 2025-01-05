package io.sdkman.config

import io.ktor.server.application.*

data class DatabaseConfig(val host: String, val port: Int, val username: String, val password: String)

data class ApiAuthenticationConfig(val username: String, val password: String)

data class ApiCacheConfig(val maxAgeSeconds: Int)

data class ApplicationConfig(
    val databaseConfig: DatabaseConfig,
    val apiAuthenticationConfig: ApiAuthenticationConfig,
    val apiCacheConfig: ApiCacheConfig
)

fun configureAppConfig(environment: ApplicationEnvironment): ApplicationConfig {
    val host: String = environment.config.property("database.host").getString()
    val port: Int = environment.config.property("database.port").getString().toInt()
    val username: String = environment.config.property("database.username").getString()
    val password: String = environment.config.property("database.password").getString()
    val apiUsername: String = environment.config.property("api.username").getString()
    val apiPassword: String = environment.config.property("api.password").getString()
    val cacheMaxAgeSeconds: Int = environment.config.property("api.cache.control").getString().toInt()
    return ApplicationConfig(
        DatabaseConfig(host, port, username, password),
        ApiAuthenticationConfig(apiUsername, apiPassword),
        ApiCacheConfig(cacheMaxAgeSeconds)
    )
}
