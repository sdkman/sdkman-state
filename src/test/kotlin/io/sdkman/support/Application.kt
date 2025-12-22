package io.sdkman.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.AuditRepositoryImpl
import io.sdkman.repos.HealthRepositoryImpl
import io.sdkman.repos.VersionsRepository

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }
        application {
            val dbConfig = configureAppConfig(environment).databaseConfig
            configureDatabase(dbConfig)
            configureRouting(VersionsRepository(), HealthRepositoryImpl(), AuditRepositoryImpl())
        }
        fn(this)
    }
}
