package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.adapter.secondary.persistence.VersionsTable
import io.sdkman.state.adapter.secondary.persistence.dbQuery
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

@Tags("acceptance")
class ConcurrentSupersessionAcceptanceSpec :
    ShouldSpec({

        should("leave exactly one visible row when a release series is published concurrently") {
            val concurrentRequests = 20
            val series = (1..concurrentRequests).map { java("25.0.$it+1") }

            withCleanDatabase {
                withTestApplication {
                    val token = JwtTestSupport.adminToken()
                    val statuses =
                        coroutineScope {
                            series
                                .map { version ->
                                    async {
                                        client
                                            .post("/versions") {
                                                contentType(ContentType.Application.Json)
                                                setBody(version.toJsonString())
                                                bearerAuth(token)
                                            }.status
                                    }
                                }.awaitAll()
                        }

                    withClue("every concurrent POST should return 204 NoContent") {
                        statuses.toSet() shouldBe setOf(HttpStatusCode.NoContent)
                    }
                }

                val visibleRows =
                    dbQuery {
                        VersionsTable
                            .selectAll()
                            .where {
                                (VersionsTable.candidate eq "java") and
                                    (VersionsTable.distribution eq Distribution.TEMURIN.name) and
                                    (VersionsTable.platform eq Platform.LINUX_X64.name) and
                                    (VersionsTable.visible eq true)
                            }.count()
                    }

                withClue("$concurrentRequests concurrent publications into one series must collapse to one visible row") {
                    visibleRows shouldBe 1L
                }
            }
        }
    })

private fun java(version: String): Version =
    Version(
        candidate = "java",
        version = version,
        platform = Platform.LINUX_X64,
        url = "https://example.com/java-$version-tem.tar.gz",
        visible = true.some(),
        distribution = Distribution.TEMURIN.some(),
    )
