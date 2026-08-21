package io.sdkman.state.adapter.secondary.persistence

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.sharedTestDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration

@Tags("integration")
class BacklogMigrationIntegrationSpec :
    ShouldSpec({

        beforeSpec {
            dropBacklogSchema()
            migrateBacklogSchemaTo("16")
            seedBacklog()
            migrateBacklogSchemaToLatest()
        }

        afterSpec {
            dropBacklogSchema()
        }

        context("V17") {
            should("hide a GraalVM row carrying a runtime-target suffix") {
                visibilityOf("25.2.4+r25", "GRAALVM") shouldBe false
            }

            should("hide every runtime-target row, not only the highest") {
                visibilityOf("21.0.9+r24", "GRAALVM") shouldBe false
            }

            should("leave the correctly versioned GraalVM row visible") {
                visibilityOf("25.0.4", "GRAALVM") shouldBe true
            }
        }

        context("V18") {
            should("keep the DISCO row of a twinned release") {
                visibilityOf("26.0.2+1.1", "LIBERICA") shouldBe true
            }

            should("retire the migrated twin of a DISCO row at the same core version") {
                visibilityOf("26.0.2", "LIBERICA") shouldBe false
            }

            should("keep the DISCO row of a twinned fx release") {
                visibilityOf("26.0.2-fx+1.1", "LIBERICA") shouldBe true
            }

            should("retire the legacy fx twin of a DISCO fx row") {
                visibilityOf("26.0.2.fx", "LIBERICA") shouldBe false
            }

            should("leave a series holding no DISCO row untouched") {
                corretto8Visibility() shouldBe listOf(true, true, true)
            }

            should("leave both rows of a tied series visible") {
                tiedEarlyAccessVisibility() shouldBe listOf(true, true)
            }
        }
    })

private const val BACKLOG_SCHEMA = "backlog_test"

private val BACKLOG_ROWS =
    listOf(
        "25.2.4+r25" to "GRAALVM",
        "21.0.9+r24" to "GRAALVM",
        "25.0.4" to "GRAALVM",
        "26.0.2" to "LIBERICA",
        "26.0.2+1.1" to "LIBERICA",
        "26.0.2.fx" to "LIBERICA",
        "26.0.2-fx+1.1" to "LIBERICA",
        "8.0.504" to "CORRETTO",
        "8.0.472" to "CORRETTO",
        "8.0.232" to "CORRETTO",
        "27.0.0+ea.34" to "OPENJDK",
        "27.0.0+ea.35" to "OPENJDK",
    )

private fun backlogFlyway(): FluentConfiguration =
    Flyway
        .configure()
        .dataSource(sharedTestDataSource)
        .schemas(BACKLOG_SCHEMA)

private fun migrateBacklogSchemaTo(target: String) {
    backlogFlyway().target(target).load().migrate()
}

private fun migrateBacklogSchemaToLatest() {
    backlogFlyway().load().migrate()
}

private fun dropBacklogSchema() {
    sharedTestDataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("DROP SCHEMA IF EXISTS $BACKLOG_SCHEMA CASCADE")
        }
    }
}

private fun seedBacklog() {
    sharedTestDataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO $BACKLOG_SCHEMA.versions (candidate, version, distribution, platform, url, visible) " +
                    "VALUES ('java', ?, ?, 'LINUX_X64', ?, true)",
            ).use { statement ->
                BACKLOG_ROWS.forEach { (version, distribution) ->
                    statement.setString(1, version)
                    statement.setString(2, distribution)
                    statement.setString(3, "https://java/$version/$distribution")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }
}

private fun visibilityOf(
    version: String,
    distribution: String,
): Boolean =
    sharedTestDataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT visible FROM $BACKLOG_SCHEMA.versions " +
                    "WHERE candidate = 'java' AND version = ? AND distribution = ?",
            ).use { statement ->
                statement.setString(1, version)
                statement.setString(2, distribution)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "no seeded row for $distribution $version" }
                    rows.getBoolean("visible")
                }
            }
    }

private fun corretto8Visibility(): List<Boolean> = listOf("8.0.504", "8.0.472", "8.0.232").map { visibilityOf(it, "CORRETTO") }

private fun tiedEarlyAccessVisibility(): List<Boolean> = listOf("27.0.0+ea.34", "27.0.0+ea.35").map { visibilityOf(it, "OPENJDK") }
