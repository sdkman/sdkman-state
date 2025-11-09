package io.sdkman

import arrow.core.None
import arrow.core.some
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

class GetVersionApiSpec : ShouldSpec({

    should("GET a UNIVERSAL version for a candidate") {
        val kotlin210Universal = Version(
            candidate = "kotlin",
            version = "2.1.0",
            platform = Platform.UNIVERSAL,
            url = "https://kotlin-2.1.0-tem",
            visible = true.some(),
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

    should("GET a platform-specific version for a candidate") {
        val java21MacX64 = Version(
            candidate = "java",
            version = "21.0.1",
            platform = Platform.MAC_X64,
            url = "https://java-21.0.1-mac-x64",
            visible = true.some(),
            vendor = "temurin".some()
        )

        withCleanDatabase {
            insertVersions(java21MacX64)
            withTestApplication {
                client.get("/versions/java/21.0.1?platform=darwinx64&vendor=temurin").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe java21MacX64.toJson()
                }
            }
        }
    }

    should("GET a version with NO vendor for a candidate") {
        val scala312Universal = Version(
            candidate = "scala",
            version = "3.1.2",
            platform = Platform.UNIVERSAL,
            url = "https://scala-3.1.2",
            visible = true.some(),
            vendor = None
        )

        withCleanDatabase {
            insertVersions(scala312Universal)
            withTestApplication {
                client.get("/versions/scala/3.1.2").apply {
                    status shouldBe HttpStatusCode.OK
                    Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe scala312Universal.toJson()
                }
            }
        }
    }

    should("return NOT_FOUND when platform-specific version does not exist") {
        withCleanDatabase {
            withTestApplication {
                client.get("/versions/java/21.0.1?platform=darwinx64&vendor=temurin").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }

    should("return NOT_FOUND when version with NO vendor does not exist") {
        withCleanDatabase {
            withTestApplication {
                client.get("/versions/scala/3.1.2").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }

    should("return BAD_REQUEST when candidate parameter is empty string") {
        withCleanDatabase {
            withTestApplication {
                client.get("/versions/%20/1.0.0").apply {
                    status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    should("return NOT_FOUND when version parameter is missing (invalid route)") {
        withCleanDatabase {
            withTestApplication {
                client.get("/versions/scala/").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }

    should("return NOT_FOUND when accessing non-existent route") {
        withCleanDatabase {
            withTestApplication {
                client.get("/versions/scala/3.1.2/extra").apply {
                    status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }
})