package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.deserializeVersionData
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

@Tags("acceptance")
class JavaAdminOverrideAcceptanceSpec :
    ShouldSpec({

        should("make the admin's older build the only visible row, holding exactly the posted tags") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val olderBuild = javaVersion(Distribution.TEMURIN, "25.0.4.0+9")

            withCleanDatabase {
                // given: the series head holds lts and 25
                insertTaggedVersion(head, "lts", "25")

                // when: the admin posts an older build with tags lts
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(olderBuild.copy(tags = listOf("lts").some()), JwtTestSupport.adminToken())
                        .status shouldBe HttpStatusCode.NoContent
                }

                // then: the older build is visible with lts only, the old head is hidden with 25 only and retired once
                visibilityOf(olderBuild) shouldBe true.some()
                tagsOf(olderBuild) shouldContainExactlyInAnyOrder listOf("lts")
                visibilityOf(head) shouldBe false.some()
                tagsOf(head) shouldContainExactlyInAnyOrder listOf("25")
                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .map { deserializeVersionData(it.versionData).version } shouldBe listOf(head.version)
            }
        }

        should("let the admin hide the only visible row of a series without promoting another") {
            val onlyVisibleRow = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val hiddenSibling = javaVersion(Distribution.TEMURIN, "25.0.4.0+9", visible = false)

            withCleanDatabase {
                // given: the only visible row of major 25 holds lts and 25, beside a hidden older build
                insertTaggedVersion(onlyVisibleRow, "lts", "25")
                insertVersions(hiddenSibling)

                // when: the admin posts the visible row with visible false and tags 25
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(
                        onlyVisibleRow.copy(visible = false.some(), tags = listOf("25").some()),
                        JwtTestSupport.adminToken(),
                    ).status shouldBe HttpStatusCode.NoContent
                }

                // then: the posted row is hidden with 25 only, and no row of major 25 is visible
                visibilityOf(onlyVisibleRow) shouldBe false.some()
                tagsOf(onlyVisibleRow) shouldContainExactlyInAnyOrder listOf("25")
                listOf(onlyVisibleRow, hiddenSibling).filter { visibilityOf(it) == true.some() }.shouldBeEmpty()
            }
        }

        should("leave the admin's posted row untagged when the post carries no tags") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")

            withCleanDatabase {
                // given: the head holds lts and 25
                insertTaggedVersion(head, "lts", "25")

                // when: the admin re-posts the head without tags
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(head, JwtTestSupport.adminToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the head stays visible and holds no tag
                visibilityOf(head) shouldBe true.some()
                tagsOf(head).shouldBeEmpty()
            }
        }

        should("let the admin hide an unreadable legacy row") {
            val legacyRow = javaVersion(Distribution.LIBERICA_NIK, "25.0.4.r25")

            withCleanDatabase {
                // given: an unreadable legacy row is visible
                insertVersions(legacyRow)

                // when: the admin posts the legacy row with visible false
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(legacyRow.copy(visible = false.some()), JwtTestSupport.adminToken())
                        .status shouldBe HttpStatusCode.NoContent
                }

                // then: the legacy row is hidden
                visibilityOf(legacyRow) shouldBe false.some()
            }
        }

        should("store a legacy admin post visible without hiding the rest of its series") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val legacyPost = javaVersion(Distribution.TEMURIN, "25.0.3")

            withCleanDatabase {
                // given: the series head is visible
                insertVersions(head)

                // when: the admin posts a version the grammar rejects
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(legacyPost, JwtTestSupport.adminToken()).status shouldBe HttpStatusCode.NoContent
                }

                // then: the legacy post and the head are both visible
                visibilityOf(legacyPost) shouldBe true.some()
                visibilityOf(head) shouldBe true.some()
            }
        }
    })
