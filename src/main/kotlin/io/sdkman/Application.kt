package io.sdkman

import io.ktor.server.application.*
import io.sdkman.config.configureAppConfig
import io.sdkman.plugins.*
import io.sdkman.repos.CandidateVersionsRepository

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val dbConfig = configureAppConfig(environment).databaseConfig

    configureDatabaseMigration(dbConfig)
    configureDatabase(dbConfig)

    configureHTTP()
    configureSerialization()
    configureRouting(CandidateVersionsRepository())
}