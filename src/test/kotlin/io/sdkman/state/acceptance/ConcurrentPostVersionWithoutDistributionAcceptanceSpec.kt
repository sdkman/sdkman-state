package io.sdkman.state.acceptance

import arrow.core.none
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
import io.sdkman.state.adapter.secondary.persistence.NA_SENTINEL
import io.sdkman.state.adapter.secondary.persistence.VersionsTable
import io.sdkman.state.adapter.secondary.persistence.dbQuery
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

/**
 * Guards the value of V15 (versions.distribution NOT NULL with 'NA' sentinel): the no-distribution
 * branch of POST /versions must now also collapse concurrent same-payload writes into a single row.
 * Pre-V15 this branch used check-then-act because Postgres' default NULLS DISTINCT semantics
 * defeated INSERT … ON CONFLICT for null-distribution rows. Post-V15 the column is non-null and the
 * UPSERT applies uniformly.
 */
@Tags("acceptance")
class ConcurrentPostVersionWithoutDistributionAcceptanceSpec :
    ShouldSpec({

        should("collapse concurrent POST /versions for the same no-distribution payload into a single row") {
            val version =
                Version(
                    candidate = "scala",
                    version = "3.4.0",
                    platform = Platform.UNIVERSAL,
                    url = "https://scala-3.4.0",
                    visible = true.some(),
                    distribution = none(),
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

                    withClue("every concurrent no-distribution POST should return 204 NoContent") {
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
                                    (VersionsTable.distribution eq NA_SENTINEL) and
                                    (VersionsTable.platform eq version.platform.name)
                            }.count()
                    }

                withClue("upsert must collapse $concurrentRequests concurrent no-distribution writes into one row") {
                    rowCount shouldBe 1L
                }
            }
        }
    })
