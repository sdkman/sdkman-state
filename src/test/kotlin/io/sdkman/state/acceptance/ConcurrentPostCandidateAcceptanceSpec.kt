package io.sdkman.state.acceptance

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Tags("acceptance")
class ConcurrentPostCandidateAcceptanceSpec :
    ShouldSpec({
        val registrationBody =
            """
            {"candidate":"jbang","name":"JBang","description":"Java scripting, without a build.","website_url":"https://jbang.dev/"}
            """.trimIndent()

        fun String.candidateEntries(): List<JsonObject> = Json.decodeFromString<JsonArray>(this).map { it.jsonObject }

        suspend fun ApplicationTestBuilder.postConcurrently(): List<HttpStatusCode> {
            val token = JwtTestSupport.adminToken()
            return coroutineScope {
                (1..2)
                    .map {
                        async {
                            client
                                .post("/admin/candidates") {
                                    contentType(ContentType.Application.Json)
                                    setBody(registrationBody)
                                    bearerAuth(token)
                                }.status
                        }
                    }.awaitAll()
            }
        }

        should("answer 201 exactly once when the same new candidate is posted concurrently") {
            withCleanDatabase {
                withTestApplication {
                    // when: two admins register the same unregistered candidate at the same time
                    val statuses = postConcurrently()

                    // then: only the caller whose insert actually created the row is told 201
                    withClue("statuses were $statuses") {
                        statuses.count { it == HttpStatusCode.Created } shouldBe 1
                    }
                }
            }
        }

        should("answer 200 to the other caller of a concurrent double post") {
            withCleanDatabase {
                withTestApplication {
                    // when: two admins register the same unregistered candidate at the same time
                    val statuses = postConcurrently()

                    // then: the loser of the race updates the row it lost to, and is told so
                    withClue("statuses were $statuses") {
                        statuses.count { it == HttpStatusCode.OK } shouldBe 1
                    }
                }
            }
        }

        should("list a concurrently registered candidate once") {
            withCleanDatabase {
                withTestApplication {
                    // given: two admins register the same unregistered candidate at the same time
                    postConcurrently()

                    // when: a client lists the candidates
                    val response = client.get("/candidates")

                    // then: the upsert collapsed both writes onto a single registry row
                    val entries = response.bodyAsText().candidateEntries()
                    val identifiers = entries.map { it.getValue("candidate").jsonPrimitive.content }
                    identifiers.count { it == "jbang" } shouldBe 1
                }
            }
        }
    })
