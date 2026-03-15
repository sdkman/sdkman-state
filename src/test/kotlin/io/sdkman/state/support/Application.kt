package io.sdkman.state.support

import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.sdkman.state.adapter.primary.rest.configureRouting
import io.sdkman.state.adapter.secondary.persistence.AuditRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.HealthRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.TagsRepositoryImpl
import io.sdkman.state.adapter.secondary.persistence.VersionsRepository
import io.sdkman.state.application.service.TagServiceImpl
import io.sdkman.state.application.service.VersionServiceImpl
import io.sdkman.state.config.configureAppConfig
import io.sdkman.state.plugins.configureDatabase

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
