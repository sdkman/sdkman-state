package io.sdkman.state.support

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration

private fun schemaFlyway(schema: String): FluentConfiguration =
    Flyway
        .configure()
        .dataSource(sharedTestDataSource)
        .schemas(schema)

fun migrateSchemaTo(
    schema: String,
    target: String,
) {
    schemaFlyway(schema).target(target).load().migrate()
}

fun migrateSchemaToLatest(schema: String) {
    schemaFlyway(schema).load().migrate()
}

fun dropSchema(schema: String) {
    sharedTestDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
