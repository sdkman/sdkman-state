package sdkman.io.plugins

import io.ktor.server.application.*

data class DatabaseConfig(val host: String, val port: Int, val username: String, val password: String)

data class ApplicationConfig(val databaseConfig: DatabaseConfig)

fun configureAppConfig(environment: ApplicationEnvironment): ApplicationConfig {
    val host: String = environment.config.property("database.host").getString()
    val port: Int = environment.config.property("database.port").getString().toInt()
    val username: String = environment.config.property("database.username").getString()
    val password: String = environment.config.property("database.password").getString()
    return ApplicationConfig(
        DatabaseConfig(host, port, username, password)
    )
}
