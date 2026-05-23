package io.sdkman.state.config

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import io.sdkman.state.adapter.secondary.persistence.VendorsTable
import io.sdkman.state.adapter.secondary.persistence.dbQuery
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.PostgresTestContainer
import io.sdkman.state.support.sharedTestDatabase
import io.sdkman.state.support.withCleanDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

@Tags("integration")
class HikariPoolIntegrationSpec :
    ShouldSpec({

        afterSpec {
            // Re-prime the shared default so subsequent specs use the working shared pool.
            sharedTestDatabase
        }

        should("serve more concurrent dbQuery calls than maxSize as connections free up") {
            withCleanDatabase {
                val maxSize = 3
                val concurrentQueries = 10
                val holdMs = 50L

                val config =
                    DefaultAppConfig(
                        MapApplicationConfig(
                            "database.host" to PostgresTestContainer.host,
                            "database.port" to PostgresTestContainer.port.toString(),
                            "database.username" to PostgresTestContainer.username,
                            "database.password" to PostgresTestContainer.password,
                            "database.pool.maxSize" to maxSize.toString(),
                            "database.pool.minIdle" to "0",
                            "database.pool.connectionTimeoutMs" to "10000",
                            "database.pool.maxLifetimeMs" to "60000",
                            "database.pool.idleTimeoutMs" to "10000",
                            "jwt.secret" to JwtTestSupport.TEST_SECRET,
                        ),
                    )

                val dataSource = createHikariDataSource(config)
                val database = Database.connect(dataSource)
                try {
                    val results =
                        runBlocking {
                            coroutineScope {
                                (1..concurrentQueries)
                                    .map {
                                        async {
                                            dbQuery {
                                                delay(holdMs)
                                                VendorsTable.selectAll().count()
                                            }
                                        }
                                    }.awaitAll()
                            }
                        }

                    results.size shouldBe concurrentQueries
                    results.all { it == 0L } shouldBe true
                } finally {
                    TransactionManager.closeAndUnregister(database)
                    dataSource.close()
                }
            }
        }
    })
