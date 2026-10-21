package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
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

@Tags("acceptance")
class JavaSupersessionRevivalAcceptanceSpec :
    ShouldSpec({

        should("bring a hidden row back as the head when it beats the head") {
            val revivedBuild = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.0+9")

            withCleanDatabase {
                // given: the newer build is stored hidden and the older head holds lts and 25
                insertVersions(revivedBuild.copy(visible = false.some()))
                insertTaggedVersion(head, "lts", "25")

                // when: DISCO re-posts the hidden build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(revivedBuild, javaVendorToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the re-posted build is the visible head holding lts and 25, the older head is hidden
                visibilityOf(revivedBuild) shouldBe true.some()
                tagsOf(revivedBuild) shouldContainAll listOf("lts", "25")
                visibilityOf(head) shouldBe false.some()
            }
        }

        should("keep the head visible and tagged and retire nothing when it is posted twice") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val disco = javaVendorToken()

            withCleanDatabase {
                // given: the head is the only visible row of its series and holds lts and 25
                insertTaggedVersion(head, "lts", "25")

                // when: DISCO posts the head twice
                withTestApplication {
                    registerCandidates("java")
                    repeat(2) { postIndex ->
                        postJavaVersion(head, disco).status shouldBe HttpStatusCode.NoContent

                        // then: the head is still visible after each post
                        withClue("visibility of the head after post ${postIndex + 1}") {
                            visibilityOf(head) shouldBe true.some()
                        }
                    }
                }

                // and: the head holds lts and 25, and no RETIRE entry is written
                tagsOf(head) shouldContainAll listOf("lts", "25")
                selectAuditRecordsByOperation(AuditOperation.RETIRE).shouldBeEmpty()
            }
        }
    })
