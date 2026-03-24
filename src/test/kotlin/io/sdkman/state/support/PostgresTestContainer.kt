package io.sdkman.state.support

import org.testcontainers.postgresql.PostgreSQLContainer

object PostgresTestContainer {
    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:16")
            .withDatabaseName("sdkman")
            .withUsername("postgres")
            .withPassword("postgres")
            .withReuse(true)

    init {
        container.start()
    }

    val host: String get() = container.host
    val port: Int get() = container.firstMappedPort
    val username: String get() = container.username
    val password: String get() = container.password
    val jdbcUrl: String get() = container.jdbcUrl
}

