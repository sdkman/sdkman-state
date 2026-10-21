package io.sdkman.state.acceptance

import arrow.core.getOrElse
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.adapter.secondary.persistence.AuditRefusalData
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.deserializeVersionData
import io.sdkman.state.support.insertTaggedVersion
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import java.util.UUID

private val DISCO_VENDOR_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
private const val DISCO_EMAIL = "disco@sdkman.io"

@Tags("acceptance")
class JavaAuditAcceptanceSpec :
    ShouldSpec({
        val disco = JwtTestSupport.vendorToken(DISCO_VENDOR_ID, DISCO_EMAIL, listOf("java"))

        should("record a new head as visible with the tags it took in its CREATE entry") {
            val previousHead = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val newHead = javaVersion(Distribution.TEMURIN, "25.0.5.0+3")

            withCleanDatabase {
                // given: the series head holds lts and 25
                insertTaggedVersion(previousHead, "lts", "25")

                // when: DISCO posts a newer build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newHead, disco).status shouldBe HttpStatusCode.NoContent
                }

                // then: the CREATE entry records the new head as visible, holding lts and 25
                val created = createEntryOf(newHead)
                created.visible shouldBe true.some()
                created.tags.getOrElse { emptyList() } shouldContainAll listOf("lts", "25")
            }
        }

        should("record a post stored below the head as hidden in its CREATE entry") {
            val head = javaVersion(Distribution.TEMURIN, "25.0.4.1+1")
            val olderBuild = javaVersion(Distribution.TEMURIN, "25.0.4.0+9")

            withCleanDatabase {
                // given: the series head is visible
                insertVersions(head)

                // when: DISCO posts an older build
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(olderBuild, disco).status shouldBe HttpStatusCode.NoContent
                }

                // then: the CREATE entry records the older build as hidden
                createEntryOf(olderBuild).visible shouldBe false.some()
            }
        }

        should("attribute the CREATE entry of a vendor post to the vendor that posted") {
            val build = javaVersion(Distribution.TEMURIN, "25.0.5.0+3")

            withCleanDatabase {
                // when: DISCO posts into an empty series
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(build, disco).status shouldBe HttpStatusCode.NoContent
                }

                // then: the only CREATE entry carries DISCO's vendor id and email
                selectAuditRecordsByOperation(AuditOperation.CREATE)
                    .map { it.vendorId to it.email } shouldBe listOf(DISCO_VENDOR_ID to DISCO_EMAIL)
            }
        }

        should("attribute the RETIRE entry of every hidden row to the vendor that posted") {
            val leftovers = listOf("8.0.504", "8.0.472", "8.0.232").map { javaVersion(Distribution.CORRETTO, it) }
            val newHead = javaVersion(Distribution.CORRETTO, "8.0.512.0+1")

            withCleanDatabase {
                // given: three leftover rows of the series are visible
                insertVersions(*leftovers.toTypedArray())

                // when: DISCO posts a build that becomes the head
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(newHead, disco).status shouldBe HttpStatusCode.NoContent
                }

                // then: each leftover has one RETIRE entry carrying DISCO's vendor id and email
                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .map { Triple(deserializeVersionData(it.versionData).version, it.vendorId, it.email) } shouldContainExactlyInAnyOrder
                    leftovers.map { Triple(it.version, DISCO_VENDOR_ID, DISCO_EMAIL) }
            }
        }

        should("audit a refused post as one REFUSE entry by the vendor naming the post and the head, and no CREATE entry") {
            val head = javaVersion(Distribution.KONA, "21.0.9.0+r17")
            val unorderable = javaVersion(Distribution.KONA, "21.0.9.0+1")

            withCleanDatabase {
                // given: the head carries build metadata of an unknown kind
                insertVersions(head)

                // when: DISCO posts a numeric build of the same core
                withTestApplication {
                    registerCandidates("java")
                    postJavaVersion(unorderable, disco).status shouldBe HttpStatusCode.Conflict
                }

                // then: one REFUSE entry, attributed to DISCO, names the posted version and the head
                selectAuditRecordsByOperation(AuditOperation.REFUSE)
                    .map { Triple(it.vendorId, it.email, Json.decodeFromString(AuditRefusalData.serializer(), it.versionData)) } shouldBe
                    listOf(
                        Triple(
                            DISCO_VENDOR_ID,
                            DISCO_EMAIL,
                            AuditRefusalData(
                                candidate = "java",
                                version = "21.0.9.0+1",
                                platform = Platform.LINUX_X64,
                                distribution = Distribution.KONA.some(),
                                head = "21.0.9.0+r17",
                            ),
                        ),
                    )

                // and: no CREATE entry is written
                selectAuditRecordsByOperation(AuditOperation.CREATE).shouldBeEmpty()
            }
        }
    })

private fun createEntryOf(version: Version): Version =
    selectAuditRecordsByOperation(AuditOperation.CREATE)
        .map { deserializeVersionData(it.versionData) }
        .single { it.version == version.version }
