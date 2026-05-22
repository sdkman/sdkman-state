package io.sdkman.state.config

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

class DefaultAppConfigUnitSpec :
    ShouldSpec({

        should("apply default pool settings when database.pool.* keys are absent") {
            val config =
                DefaultAppConfig(
                    MapApplicationConfig(
                        "jwt.secret" to "any",
                    ),
                )

            config.databasePoolMaxSize shouldBe 20
            config.databasePoolMinIdle shouldBe 2
            config.databasePoolConnectionTimeoutMs shouldBe 5_000L
            config.databasePoolMaxLifetimeMs shouldBe 1_800_000L
            config.databasePoolIdleTimeoutMs shouldBe 600_000L
        }

        should("override pool settings from supplied configuration") {
            val config =
                DefaultAppConfig(
                    MapApplicationConfig(
                        "database.pool.maxSize" to "50",
                        "database.pool.minIdle" to "5",
                        "database.pool.connectionTimeoutMs" to "10000",
                        "database.pool.maxLifetimeMs" to "900000",
                        "database.pool.idleTimeoutMs" to "120000",
                        "jwt.secret" to "any",
                    ),
                )

            config.databasePoolMaxSize shouldBe 50
            config.databasePoolMinIdle shouldBe 5
            config.databasePoolConnectionTimeoutMs shouldBe 10_000L
            config.databasePoolMaxLifetimeMs shouldBe 900_000L
            config.databasePoolIdleTimeoutMs shouldBe 120_000L
        }
    })
