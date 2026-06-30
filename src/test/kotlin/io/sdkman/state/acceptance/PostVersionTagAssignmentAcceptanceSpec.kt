package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.request.bearerAuth
import io.ktor.http.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.TagAssignment
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.*
import io.sdkman.state.support.JwtTestSupport

@Tags("acceptance")
class PostVersionTagAssignmentAcceptanceSpec :
    ShouldSpec({

        should("assign a new tag to an existing version and return 204 No Content") {
            val candidate = "java"
            val version = "27.0.2"
            val distribution = Distribution.TEMURIN
            val platform = Platform.LINUX_X64

            withCleanDatabase {
                // given: a version with no tags
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = candidate,
                            version = version,
                            platform = platform,
                            url = "https://java-27.0.2-tem",
                            visible = true.some(),
                            distribution = distribution.some(),
                        ),
                    )

                // when: assigning a tag to the version
                withTestApplication {
                    val response =
                        client.post("/versions/tags") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                TagAssignment(
                                    candidate = candidate,
                                    version = version,
                                    distribution = distribution.some(),
                                    platform = platform,
                                    tag = "latest",
                                ).toJsonString(),
                            )
                            bearerAuth(JwtTestSupport.adminToken())
                        }

                    // then: 204 No Content
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // and: the version carries the assigned tag
                selectTagNames(versionId) shouldContain "latest"
            }
        }
    })
