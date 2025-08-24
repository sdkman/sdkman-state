package io.sdkman

import arrow.core.None
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.sdkman.domain.Platform
import io.sdkman.domain.Version
import io.sdkman.support.insertVersions
import io.sdkman.support.toJson
import io.sdkman.support.withCleanDatabase
import io.sdkman.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

//TODO: add a test for a platform-specific version
//TODO: add a test for a version with NO vendor
class GetVersionApiSpec : ShouldSpec({

    should("GET a UNIVERSAL version for a candidate") {
        val kotlin210Universal = Version(
            candidate = "kotlin",
            version = "2.1.0",
            platform = Platform.UNIVERSAL,
            url = "https://kotlin-2.1.0-tem",
            visible = true,
            vendor = None
        )

        withCleanDatabase {
            insertVersions(kotlin210Universal)
            withTestApplication {
                client.get("/versions/kotlin/2.1.0").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe kotlin210Universal.toJson()
                }
            }
        }
    }
})