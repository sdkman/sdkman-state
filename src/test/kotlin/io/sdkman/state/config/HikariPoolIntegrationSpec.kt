package io.sdkman.state.config

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.adapter.secondary.persistence.VendorsTable
import io.sdkman.state.adapter.secondary.persistence.dbQuery
import io.sdkman.state.support.sharedTestDatabase
import io.sdkman.state.support.testApplicationConfig
import io.sdkman.state.support.withCleanDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager

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
                        testApplicationConfig().apply {
                            put("database.pool.maxSize", maxSize.toString())
                            put("database.pool.connectionTimeoutMs", "10000")
                        },
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
