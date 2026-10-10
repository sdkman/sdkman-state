package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class UnknownRoleAuthorizationAcceptanceSpec :
    ShouldSpec({
        val taggedVersion =
            Version(
                candidate = "java",
                version = "17.0.1.0",
                platform = Platform.LINUX_X64,
                url = "https://java-17.0.1.0-tem",
                visible = true.some(),
                distribution = Distribution.TEMURIN.some(),
                tags = listOf("lts").some(),
            )
        val uniqueVersionBody = """{"candidate":"java","version":"17.0.1.0","platform":"LINUX_X64","distribution":"TEMURIN"}"""
        val uniqueTagBody = """{"candidate":"java","tag":"lts","distribution":"TEMURIN","platform":"LINUX_X64"}"""
        val tagAssignmentBody =
            TagAssignment(
                candidate = "java",
                version = "17.0.1.0",
                distribution = Distribution.TEMURIN.some(),
                platform = Platform.LINUX_X64,
                tag = "latest",
            ).toJsonString()
        val observerToken = JwtTestSupport.tokenWithClaims(role = "observer", candidates = listOf("java"))

        suspend fun withTaggedVersion(block: suspend ApplicationTestBuilder.() -> Unit) =
            withCleanDatabase {
                withTestApplication {
                    // given: a registered candidate with a tagged version an admin may write
                    registerCandidates("java")
                    client.post("/versions") {
                        contentType(ContentType.Application.Json)
                        setBody(taggedVersion.toJsonString())
                        bearerAuth(JwtTestSupport.adminToken())
                    }
                    block()
                }
            }

        should("refuse an unknown role posting a version with 403 Forbidden") {
            withTaggedVersion {
                val response =
                    client.post("/versions") {
                        contentType(ContentType.Application.Json)
                        setBody(taggedVersion.toJsonString())
                        bearerAuth(observerToken)
                    }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse an unknown role deleting a version with 403 Forbidden") {
            withTaggedVersion {
                val response =
                    client.delete("/versions") {
                        contentType(ContentType.Application.Json)
                        setBody(uniqueVersionBody)
                        bearerAuth(observerToken)
                    }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse an unknown role assigning a tag with 403 Forbidden") {
            withTaggedVersion {
                val response =
                    client.post("/versions/tags") {
                        contentType(ContentType.Application.Json)
                        setBody(tagAssignmentBody)
                        bearerAuth(observerToken)
                    }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        should("refuse an unknown role deleting a tag with 403 Forbidden") {
            withTaggedVersion {
                val response =
                    client.delete("/versions/tags") {
                        contentType(ContentType.Application.Json)
                        setBody(uniqueTagBody)
                        bearerAuth(observerToken)
                    }

                response.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
