package io.sdkman.support

import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.VersionsRepository

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    testApplication {
        application {
            val dbConfig = configureAppConfig(environment).databaseConfig
            configureDatabase(dbConfig)
            configureRouting(VersionsRepository())
        }
        fn(this)
    }
}