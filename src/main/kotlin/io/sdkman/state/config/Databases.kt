package io.sdkman.state.config

import arrow.core.getOrElse
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import javax.sql.DataSource

fun createHikariDataSource(config: AppConfig): HikariDataSource {
    val hikariConfig =
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.databaseUsername.getOrElse { "" }
            password = config.databasePassword.getOrElse { "" }
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.databasePoolMaxSize
            minimumIdle = config.databasePoolMinIdle
            connectionTimeout = config.databasePoolConnectionTimeoutMs
            maxLifetime = config.databasePoolMaxLifetimeMs
            idleTimeout = config.databasePoolIdleTimeoutMs
            poolName = "sdkman-state-pool"
            initializationFailTimeout = -1
        }
    return HikariDataSource(hikariConfig)
}

fun Application.configureDatabase(dataSource: DataSource): Database =
    Database.connect(
        datasource = dataSource,
        databaseConfig =
            DatabaseConfig {
                defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
            },
    )
