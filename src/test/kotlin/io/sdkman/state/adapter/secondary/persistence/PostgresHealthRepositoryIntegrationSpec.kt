package io.sdkman.state.adapter.secondary.persistence

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.withCleanDatabase
import kotlinx.coroutines.runBlocking

@Tags("integration")
class PostgresHealthRepositoryIntegrationSpec :
    ShouldSpec({

        should("successfully check database connection when database is available") {
            withCleanDatabase {
                val healthRepo = PostgresHealthRepository()

                runBlocking {
                    val result = healthRepo.checkDatabaseConnection()
                    result.isRight() shouldBe true
                }
            }
        }
    })
