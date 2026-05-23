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
import io.sdkman.state.adapter.secondary.persistence.VersionTagsTable
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
 * Guards spec R5: the version write and the tag replacement run in a single transaction so a
 * tag-replacement failure rolls back the version write — and, equally, concurrent same-payload
 * writes never leave orphan or duplicate tag rows behind. Fires N coroutines posting the same
 * `(candidate, version, distribution, platform)` payload **with tags**, asserts every response
 * is 204 No Content, then verifies that exactly one version row survives and the surviving tag
 * rows match the posted tag list (no orphan rows from partially-applied attempts).
 */
@Tags("acceptance")
class ConcurrentPostVersionWithTagsAcceptanceSpec :
    ShouldSpec({

        should("collapse concurrent POST /versions with tags into a single row and tag set") {
            val tags = listOf("latest", "lts", "21")
            val version =
                Version(
                    candidate = "java",
                    version = "21.0.5",
                    platform = Platform.LINUX_X64,
                    url = "https://java-21.0.5-temurin",
                    visible = true.some(),
                    distribution = Distribution.TEMURIN.some(),
                    tags = tags.some(),
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

                val tagRows =
                    dbQuery {
                        VersionTagsTable
                            .selectAll()
                            .where {
                                (VersionTagsTable.candidate eq version.candidate) and
                                    (VersionTagsTable.distribution eq Distribution.TEMURIN.name) and
                                    (VersionTagsTable.platform eq version.platform.name)
                            }.map { it[VersionTagsTable.tag] to it[VersionTagsTable.versionId] }
                    }

                withClue("tag rows must match the single surviving write — no orphans, no duplicates") {
                    tagRows.map { it.first }.sorted() shouldBe tags.sorted()
                    tagRows.map { it.second }.toSet().size shouldBe 1
                }
            }
        }
    })
