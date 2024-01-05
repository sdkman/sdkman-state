package sdkman.io

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*
import sdkman.io.plugins.*

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            val dbConfig = configureAppConfig(environment).databaseConfig
            configureDatabaseMigration(dbConfig)
            configureDatabase(dbConfig)
            configureRouting(CandidateVersionsRepository())
        }
        client.get("/candidates/java").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("[]", bodyAsText())
        }
    }
}
