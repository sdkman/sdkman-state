package io.sdkman.support

import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.VersionsRepository
import io.sdkman.repos.HealthRepositoryImpl

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    testApplication {
        application {
            val dbConfig = configureAppConfig(environment).databaseConfig
            configureDatabase(dbConfig)
            configureRouting(VersionsRepository(), HealthRepositoryImpl())
        }
        fn(this)
    }
}