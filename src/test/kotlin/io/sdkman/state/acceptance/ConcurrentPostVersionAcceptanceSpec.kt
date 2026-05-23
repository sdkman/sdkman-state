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

/**
 * Guards spec R4: concurrent POST /versions for the same (candidate, version, distribution, platform)
 * must collapse to a single row via the Postgres UPSERT, with every caller seeing 204 No Content
 * (i.e. no unique-constraint exception ever leaks back to the client).
 *
 * Run repeatedly (`./gradlew test --tests '*ConcurrentPostVersion*' --rerun`) to flush out any
 * surviving check-then-act race window.
 */
@Tags("acceptance")
class ConcurrentPostVersionAcceptanceSpec :
    ShouldSpec({

        should("collapse concurrent POST /versions for the same payload into a single row") {
            val version =
                Version(
                    candidate = "java",
                    version = "21.0.4",
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.4-temurin",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    md5sum = "abc123def456abc123def456abc123de".some(),
                )
            val requestBody = version.toJsonString()
            val concurrentRequests = 20

            withCleanDatabase {
                withTestApplication {
                    val token = JwtTestSupport.adminToken()
                    val statuses =
                        coroutineScope {
                            (1..concurrentRequests)
                                .map {
                                    async {
                                        client
                                            .post("/versions") {
                                                contentType(ContentType.Application.Json)
                                                setBody(requestBody)
                                                bearerAuth(token)
                                            }.status
                                    }
                                }.awaitAll()
                        }

                    withClue("every concurrent POST should return 204 NoContent") {
                        statuses.toSet() shouldBe setOf(HttpStatusCode.NoContent)
                    }
                }

                val rowCount =
                    dbQuery {
                        VersionsTable
                            .selectAll()
                            .where {
                                (VersionsTable.candidate eq version.candidate) and
                                    (VersionsTable.version eq version.version) and
                                    (VersionsTable.distribution eq Distribution.TEMURIN.name) and
                                    (VersionsTable.platform eq version.platform.name)
                            }.count()
                    }

                withClue("upsert must collapse $concurrentRequests concurrent writes into one row") {
                    rowCount shouldBe 1L
                }
            }
        }
    })
