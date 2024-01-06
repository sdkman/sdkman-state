package sdkman.io

import io.ktor.server.application.*
import sdkman.io.config.configureAppConfig
import sdkman.io.plugins.*
import sdkman.io.repos.CandidateVersionsRepository

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val dbConfig = configureAppConfig(environment).databaseConfig

    configureDatabaseMigration(dbConfig)
    configureDatabase(dbConfig)

    configureHTTP()
    configureSerialization()
    configureRouting(CandidateVersionsRepository())
}