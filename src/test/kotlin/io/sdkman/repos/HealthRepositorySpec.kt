package io.sdkman.repos

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.support.withCleanDatabase
import kotlinx.coroutines.runBlocking

class HealthRepositorySpec : ShouldSpec({

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