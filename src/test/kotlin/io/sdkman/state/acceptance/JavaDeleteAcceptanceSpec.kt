package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.adapter.primary.rest.dto.TagConflictResponse
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.deleteJavaVersion
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json

@Tags("acceptance")
class JavaDeleteAcceptanceSpec :
    ShouldSpec({

        should("let DISCO delete a hidden untagged row with 204") {
            val hiddenRow = javaVersion(Distribution.TEMURIN, "25.0.3.0", visible = false)

            withCleanDatabase {
                // given: TEMURIN 25.0.3.0 is hidden and untagged
                insertVersions(hiddenRow)

                // when: DISCO deletes it
                withTestApplication {
                    deleteJavaVersion(hiddenRow, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the row is gone
                visibilityOf(hiddenRow) shouldBe none()
            }
        }

        should("answer 404 to a delete of a row that does not exist") {
            val missingRow = javaVersion(Distribution.TEMURIN, "25.0.3.0")

            withCleanDatabase {
                // when: DISCO deletes TEMURIN 25.0.3.0, which was never stored
                withTestApplication {
                    val response = deleteJavaVersion(missingRow, javaVendorToken())

                    // then: the row is not found
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        should("refuse with 400 a delete whose version is blank") {
            val blankVersionRow = javaVersion(Distribution.TEMURIN, " ")

            withCleanDatabase {
                // when: DISCO deletes a TEMURIN row by a blank version
                withTestApplication {
                    val response = deleteJavaVersion(blankVersionRow, javaVendorToken())

                    // then: the delete names the empty version
                    response.status shouldBe HttpStatusCode.BadRequest
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()) shouldBe
                        ErrorResponse("Bad Request", "version cannot be empty")
                }
            }
        }

        should("refuse with 409 the admin's delete of a row tagged lts and keep the row") {
            val taggedRow = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")

            withCleanDatabase {
                // given: the row holds lts
                insertTaggedVersion(taggedRow, "lts")

                // when: the admin deletes it
                withTestApplication {
                    val response = deleteJavaVersion(taggedRow, JwtTestSupport.adminToken())

                    // then: the delete conflicts with the tag, naming it
                    response.status shouldBe HttpStatusCode.Conflict
                    Json.decodeFromString<TagConflictResponse>(response.bodyAsText()) shouldBe
                        TagConflictResponse(
                            error = "Conflict",
                            message = "Cannot delete version with active tags. Remove or reassign the following tags first.",
                            tags = listOf("lts"),
                        )
                }

                // and: the row is still stored and visible
                visibilityOf(taggedRow) shouldBe true.some()
            }
        }

        should("leave the series without a visible member when the admin deletes its head") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val hiddenMembers =
                listOf(
                    javaVersion(Distribution.TEMURIN, "25.0.4.0+9", visible = false),
                    javaVersion(Distribution.TEMURIN, "25.0.3.0+7", visible = false),
                )

            withCleanDatabase {
                // given: an untagged head and two hidden members of its series
                insertVersions(head, *hiddenMembers.toTypedArray())

                // when: the admin deletes the head
                withTestApplication {
                    deleteJavaVersion(head, JwtTestSupport.adminToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the delete promotes no hidden member
                (listOf(head) + hiddenMembers).filter { visibilityOf(it) == true.some() }.shouldBeEmpty()
            }
        }

        should("let DISCO delete a legacy spelling outside the grammar with 204") {
            val legacyRow = javaVersion(Distribution.TEMURIN, "25.0.3")

            withCleanDatabase {
                // given: TEMURIN 25.0.3 is stored in its legacy three-component spelling
                insertVersions(legacyRow)

                // when: DISCO deletes it by that spelling
                withTestApplication {
                    deleteJavaVersion(legacyRow, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the row is gone
                visibilityOf(legacyRow) shouldBe none()
            }
        }

        should("let DISCO delete a row without a distribution by a body of only version and platform with 204") {
            val rowWithoutDistribution = javaVersion(Distribution.TEMURIN, "25.0.3.0", visible = false).copy(distribution = none())

            withCleanDatabase {
                // given: 25.0.3.0 is hidden, untagged and stored without a distribution
                insertVersions(rowWithoutDistribution)

                // when: DISCO deletes it by a body that names no distribution
                withTestApplication {
                    val response =
                        client.delete("/versions/java") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"version":"25.0.3.0","platform":"LINUX_X64"}""")
                            bearerAuth(javaVendorToken())
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }

                // then: the row is gone
                visibilityOf(rowWithoutDistribution) shouldBe none()
            }
        }

        should("refuse with 400 a delete body that carries candidate java and keep the row") {
            val storedRow = javaVersion(Distribution.TEMURIN, "25.0.3.0")

            withCleanDatabase {
                // given: TEMURIN 25.0.3.0 is visible and untagged
                insertVersions(storedRow)

                // when: DISCO deletes it by a body that also names candidate java
                withTestApplication {
                    val response =
                        client.delete("/versions/java") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"candidate":"java","version":"25.0.3.0","platform":"LINUX_X64","distribution":"TEMURIN"}""")
                            bearerAuth(javaVendorToken())
                        }

                    // then: the body is rejected
                    response.status shouldBe HttpStatusCode.BadRequest
                }

                // and: the row is still stored and visible
                visibilityOf(storedRow) shouldBe true.some()
            }
        }
    })
