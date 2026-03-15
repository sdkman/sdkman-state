package io.sdkman.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.configureDatabase
import io.sdkman.plugins.configureRouting
import io.sdkman.repos.AuditRepositoryImpl
import io.sdkman.repos.HealthRepositoryImpl
import io.sdkman.repos.TagsRepositoryImpl
import io.sdkman.repos.VersionsRepository
import io.sdkman.service.TagServiceImpl
import io.sdkman.service.VersionServiceImpl

fun withTestApplication(fn: suspend (ApplicationTestBuilder.() -> Unit)) {
    testApplication {
        environment {
            config = ApplicationConfig("application.conf")
        }
        application {
            val dbConfig = configureAppConfig(environment).databaseConfig
            configureDatabase(dbConfig)

            val versionsRepo = VersionsRepository()
            val tagsRepo = TagsRepositoryImpl()
            val auditRepo = AuditRepositoryImpl()

            configureRouting(
                versionService = VersionServiceImpl(versionsRepo, tagsRepo, auditRepo),
                tagService = TagServiceImpl(tagsRepo, auditRepo),
                healthRepo = HealthRepositoryImpl(),
            )
        }
        fn(this)
    }
}
