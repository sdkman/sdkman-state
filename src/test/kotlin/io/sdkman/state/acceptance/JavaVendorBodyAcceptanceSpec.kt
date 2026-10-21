package io.sdkman.state.acceptance

import arrow.core.none
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.sdkman.state.adapter.primary.rest.dto.ValidationErrorResponse
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaBody
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class JavaVendorBodyAcceptanceSpec :
    ShouldSpec({

        should("refuse a vendor post carrying tags and store nothing") {
            withCleanDatabase {
                // when: DISCO posts a build with tags
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaBody(temurinBodyOf(POSTED_VERSION, """"tags": ["lts"]"""), javaVendorToken())

                    // then: the validation error identifies the tags field
                    response.status shouldBe HttpStatusCode.BadRequest
                    failedFieldsOf(response) shouldBe listOf("tags")
                }

                // and: the build does not exist
                visibilityOf(javaVersion(Distribution.TEMURIN, POSTED_VERSION)) shouldBe none()
            }
        }

        should("refuse a vendor post carrying visible and store nothing") {
            withCleanDatabase {
                // when: DISCO posts a build with visible true
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaBody(temurinBodyOf(POSTED_VERSION, """"visible": true"""), javaVendorToken())

                    // then: the validation error identifies the visible field
                    response.status shouldBe HttpStatusCode.BadRequest
                    failedFieldsOf(response) shouldBe listOf("visible")
                }

                // and: the build does not exist
                visibilityOf(javaVersion(Distribution.TEMURIN, POSTED_VERSION)) shouldBe none()
            }
        }

        should("refuse a vendor version outside the grammar") {
            withCleanDatabase {
                // when: DISCO posts a three-component version
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaBody(temurinBodyOf("25.0.5"), javaVendorToken())

                    // then: the validation error identifies the version field
                    response.status shouldBe HttpStatusCode.BadRequest
                    failedFieldsOf(response) shouldBe listOf("version")
                }
            }
        }

        should("refuse candidate in the body of a vendor post") {
            withCleanDatabase {
                // when: DISCO posts a build naming its candidate
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaBody(temurinBodyOf(POSTED_VERSION, """"candidate": "java""""), javaVendorToken())

                    // then: the validation error identifies the candidate field
                    response.status shouldBe HttpStatusCode.BadRequest
                    failedFieldsOf(response) shouldBe listOf("candidate")
                }
            }
        }

        should("refuse candidate in the body of an admin post") {
            withCleanDatabase {
                // when: the admin posts a build naming its candidate
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaBody(temurinBodyOf(POSTED_VERSION, """"candidate": "java""""), JwtTestSupport.adminToken())

                    // then: the validation error identifies the candidate field
                    response.status shouldBe HttpStatusCode.BadRequest
                    failedFieldsOf(response) shouldBe listOf("candidate")
                }
            }
        }
    })

private const val POSTED_VERSION = "25.0.5.0+3"

private fun temurinBodyOf(
    version: String,
    vararg extraMembers: String,
): String =
    (
        listOf(
            """"version": "$version"""",
            """"distribution": "TEMURIN"""",
            """"platform": "LINUX_X64"""",
            """"url": "https://example.com/java-$version-temurin-linux_x64.tar.gz"""",
        ) + extraMembers
    ).joinToString(prefix = "{", postfix = "}")

private suspend fun failedFieldsOf(response: HttpResponse): List<String> =
    Json.decodeFromString<ValidationErrorResponse>(response.bodyAsText()).failures.map { it.field }
