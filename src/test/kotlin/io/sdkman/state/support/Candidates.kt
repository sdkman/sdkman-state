package io.sdkman.state.support

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder

suspend fun ApplicationTestBuilder.registerCandidates(vararg candidates: String) {
    candidates.forEach { candidate ->
        val response =
            client.post("/admin/candidates") {
                contentType(ContentType.Application.Json)
                setBody(registrationBody(candidate))
                bearerAuth(JwtTestSupport.adminToken())
            }

        withClue("registering the candidate '$candidate' should succeed") {
            response.status shouldBeIn listOf(HttpStatusCode.Created, HttpStatusCode.OK)
        }
    }
}

private fun registrationBody(candidate: String): String {
    val name = candidate.replaceFirstChar { it.uppercase() }
    return """
        {"candidate":"$candidate","name":"$name","description":"The $name candidate, registered by a test.","website_url":"https://sdkman.io/candidates/$candidate"}
        """.trimIndent()
}
