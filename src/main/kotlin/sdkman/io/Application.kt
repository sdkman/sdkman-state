package sdkman.io

import io.ktor.server.application.*
import sdkman.io.plugins.*

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    val dbConfig = configureAppConfig(environment).databaseConfig

    configureDatabaseMigration(dbConfig)
    configureDatabase(dbConfig)

    configureHTTP()
    configureSerialization()
    configureRouting(CandidateVersionsRepository())
}