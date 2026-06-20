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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Guards the dedup of the no-distribution branch of POST /versions: concurrent same-payload writes
 * must collapse into a single row. V16 converged the 'NA' sentinel back to SQL NULL and recreated the
 * unique constraint as UNIQUE NULLS NOT DISTINCT, so Postgres treats the NULL distributions as
 * colliding and INSERT … ON CONFLICT still dedups. Before NULLS NOT DISTINCT, Postgres' default
 * NULLS DISTINCT semantics defeated the UPSERT for null-distribution rows.
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
                                    VersionsTable.distribution.isNull() and
                                    (VersionsTable.platform eq version.platform.name)
                            }.count()
                    }

                withClue("upsert must collapse $concurrentRequests concurrent no-distribution writes into one row") {
                    rowCount shouldBe 1L
                }
            }
        }
    })
