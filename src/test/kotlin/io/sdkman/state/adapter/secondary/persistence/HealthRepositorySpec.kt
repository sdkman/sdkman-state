package io.sdkman.state.adapter.secondary.persistence

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.support.withCleanDatabase
import kotlinx.coroutines.runBlocking

class HealthRepositorySpec :
    ShouldSpec({

        should("successfully check database connection when database is available") {
            withCleanDatabase {
                val healthRepo = HealthRepositoryImpl()

                runBlocking {
                    val result = healthRepo.checkDatabaseConnection()
                    result.isRight() shouldBe true
                }
            }
        }
    })
