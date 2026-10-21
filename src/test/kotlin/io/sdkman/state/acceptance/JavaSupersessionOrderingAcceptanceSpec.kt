package io.sdkman.state.acceptance

import arrow.core.none
import arrow.core.some
import com.auth0.jwt.JWT
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.sdkman.state.adapter.primary.rest.dto.ErrorResponse
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.deserializeVersionData
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import java.util.UUID

@Tags("acceptance")
class JavaSupersessionOrderingAcceptanceSpec :
    ShouldSpec({

        should("make a newer build the head and move the series tags onto it") {
            val previousHead = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val newerBuild = javaVersion(Distribution.TEMURIN, "25.0.5.0+3")
            val disco = javaVendorToken()

            withCleanDatabase {
                // given: the series head holds lts and 25
                insertTaggedVersion(previousHead, "lts", "25")

                // when: DISCO posts a newer build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newerBuild, disco).status shouldBe HttpStatusCode.NoContent
                }

                // then: the newer build is the visible head holding the tags, the old head is retired by DISCO
                visibilityOf(newerBuild) shouldBe true.some()
                tagsOf(newerBuild) shouldContainAll listOf("lts", "25")
                visibilityOf(previousHead) shouldBe false.some()
                tagsOf(previousHead).shouldBeEmpty()
                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .filter { deserializeVersionData(it.versionData).version == previousHead.version }
                    .map { it.vendorId } shouldBe listOf(vendorIdOf(disco))
            }
        }

        should("store an older build hidden and leave the head as it was") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val olderBuild = javaVersion(Distribution.TEMURIN, "25.0.4.0+9")

            withCleanDatabase {
                // given: the series head holds lts and 25
                insertTaggedVersion(head, "lts", "25")

                // when: DISCO posts an older build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(olderBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the older build is hidden and untagged, the head keeps its visibility and tags
                visibilityOf(olderBuild) shouldBe false.some()
                tagsOf(olderBuild).shouldBeEmpty()
                visibilityOf(head) shouldBe true.some()
                tagsOf(head) shouldContainAll listOf("lts", "25")
            }
        }

        should("let a GA build supersede its early-access build") {
            val earlyAccess = javaVersion(Distribution.OPENJDK, "28.0.0.0+ea.20")
            val generalAvailability = javaVersion(Distribution.OPENJDK, "28.0.0.0+35")

            withCleanDatabase {
                // given: the early-access build is the head holding 28 and latest
                insertTaggedVersion(earlyAccess, "28", "latest")

                // when: DISCO posts the GA build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(generalAvailability, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the GA build is the visible head holding the tags, the early-access build is hidden
                visibilityOf(generalAvailability) shouldBe true.some()
                tagsOf(generalAvailability) shouldContainAll listOf("28", "latest")
                visibilityOf(earlyAccess) shouldBe false.some()
            }
        }

        should("never let an early-access build supersede its GA build") {
            val generalAvailability = javaVersion(Distribution.OPENJDK, "28.0.0.0+35")
            val earlyAccess = javaVersion(Distribution.OPENJDK, "28.0.0.0+ea.21")

            withCleanDatabase {
                // given: the GA build is the head
                insertVersions(generalAvailability)

                // when: DISCO posts an early-access build of the same core
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(earlyAccess, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the early-access build is hidden and the GA build stays visible
                visibilityOf(earlyAccess) shouldBe false.some()
                visibilityOf(generalAvailability) shouldBe true.some()
            }
        }

        should("compare build numbers numerically rather than as text") {
            val head = javaVersion(Distribution.LIBERICA, "21.0.12+1.10")
            val lowerBuild = javaVersion(Distribution.LIBERICA, "21.0.12.0+1.9")

            withCleanDatabase {
                // given: the head is build 1.10
                insertVersions(head)

                // when: DISCO posts build 1.9, which sorts higher as text
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(lowerBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: build 1.9 is hidden and build 1.10 stays visible
                visibilityOf(lowerBuild) shouldBe false.some()
                visibilityOf(head) shouldBe true.some()
            }
        }

        should("refuse with 409 a post that cannot be ordered against the head") {
            val head = javaVersion(Distribution.KONA, "21.0.9.0+r17")
            val unorderable = javaVersion(Distribution.KONA, "21.0.9.0+1")

            withCleanDatabase {
                // given: the head carries build metadata of an unknown kind
                insertVersions(head)

                // when: DISCO posts a numeric build of the same core
                withTestApplication {
                    registerCandidates("java")
                    val response = postJavaVersion(unorderable, javaVendorToken())

                    // then: the post is refused, naming the head
                    response.status shouldBe HttpStatusCode.Conflict
                    Json.decodeFromString<ErrorResponse>(response.bodyAsText()).message shouldContain head.version
                }

                // and: nothing is written, the head stays visible
                visibilityOf(unorderable) shouldBe none()
                visibilityOf(head) shouldBe true.some()
            }
        }
    })

private fun vendorIdOf(token: String): UUID = UUID.fromString(JWT.decode(token).getClaim("vendor_id").asString())
