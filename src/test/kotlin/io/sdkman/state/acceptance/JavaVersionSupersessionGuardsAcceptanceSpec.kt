package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.domain.model.AuditOperation
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.deserializeVersionData
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.selectAuditRecordsByOperation
import io.sdkman.state.support.selectTagNames
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication

/**
 * Covers the guards that stop supersession from firing, and the trail it leaves behind:
 * a hidden publication (R11), an ineligible publication (R11a) and a candidate other
 * than java (R1) retire nothing, a re-post of the current version is idempotent (R14),
 * retirement leaves tags alone (R16), and every retired row produces its own `RETIRE`
 * audit entry attributed to the triggering POST (R15).
 *
 * The core rule and the series boundaries live in `JavaVersionSupersessionAcceptanceSpec`;
 * concurrency lives in its own spec.
 */
@Tags("acceptance")
class JavaVersionSupersessionGuardsAcceptanceSpec :
    ShouldSpec({

        should("leave the series alone when the publication is hidden") {
            // given: a visible migrated row that a hidden publication must not empty out
            val migrated = liberica("26.0.2")
            val hidden = liberica("26.0.2+1.1").copy(visible = false.some())

            withCleanDatabase {
                insertVersions(migrated)
                withTestApplication { postVersion(hidden).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(migrated) shouldBe true.some()
            }
        }

        should("keep the current version visible when it is re-posted") {
            // given: the series has already been superseded once
            val current = liberica("26.0.2+1.1")

            withCleanDatabase {
                insertVersions(liberica("26.0.2").copy(visible = false.some()), current)
                withTestApplication { postVersion(current).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(current) shouldBe true.some()
            }
        }

        should("record no retirement when the current version is re-posted") {
            // given: the row the re-post would retire is the posted row itself, so it is excluded
            val current = liberica("26.0.2+1.1")

            withCleanDatabase {
                insertVersions(liberica("26.0.2").copy(visible = false.some()), current)
                withTestApplication { postVersion(current).status shouldBe HttpStatusCode.NoContent }

                selectAuditRecordsByOperation(AuditOperation.RETIRE) shouldHaveSize 0
            }
        }

        should("leave the series alone when the posted variant is outside the series vocabulary") {
            // given: `jfr` is valid semverish but is not an `fx`/`crac` variant, so the
            // publication belongs to no series and supersedes nothing
            val migrated = liberica("26.0.2")

            withCleanDatabase {
                insertVersions(migrated)
                withTestApplication { postVersion(liberica("26.0.2-jfr+1.1")).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(migrated) shouldBe true.some()
            }
        }

        should("leave prior versions of another candidate visible") {
            // given: supersession is fixed to java, so a gradle publication is additive as before
            val previous = gradle("8.13")

            withCleanDatabase {
                insertVersions(previous)
                withTestApplication { postVersion(gradle("8.14")).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(previous) shouldBe true.some()
            }
        }

        should("retire a tagged version like any other member of its series") {
            val tagged = temurin("21.0.12")

            withCleanDatabase {
                val taggedId = insertVersionWithId(tagged)
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, taggedId)
                withTestApplication { postVersion(temurin("21.0.12+1.1")).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(tagged) shouldBe false.some()
            }
        }

        should("leave the tag of a retired version in place") {
            // given: retirement neither moves, copies nor clears a tag — the tag endpoints
            // re-point it when the new version is tagged, which is a separate call
            withCleanDatabase {
                val taggedId = insertVersionWithId(temurin("21.0.12"))
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.LINUX_X64, taggedId)
                withTestApplication { postVersion(temurin("21.0.12+1.1")).status shouldBe HttpStatusCode.NoContent }

                selectTagNames(taggedId) shouldBe listOf("lts")
            }
        }

        should("record one RETIRE audit entry per retired version") {
            withCleanDatabase {
                insertVersions(kona("25.0.1+1"), kona("25.0.2+1"))
                withTestApplication { postVersion(kona("25.0.4+1")).status shouldBe HttpStatusCode.NoContent }

                selectAuditRecordsByOperation(AuditOperation.RETIRE) shouldHaveSize 2
            }
        }

        should("audit the identifier of every retired version") {
            withCleanDatabase {
                insertVersions(kona("25.0.1+1"), kona("25.0.2+1"))
                withTestApplication { postVersion(kona("25.0.4+1")).status shouldBe HttpStatusCode.NoContent }

                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .map { deserializeVersionData(it.versionData).version }
                    .sorted() shouldBe listOf("25.0.1+1", "25.0.2+1")
            }
        }

        should("attribute every RETIRE audit entry to the posting vendor") {
            withCleanDatabase {
                insertVersions(kona("25.0.1+1"), kona("25.0.2+1"))
                withTestApplication { postVersion(kona("25.0.4+1")).status shouldBe HttpStatusCode.NoContent }

                selectAuditRecordsByOperation(AuditOperation.RETIRE)
                    .map { it.vendorId } shouldBe listOf(JwtTestSupport.NIL_UUID, JwtTestSupport.NIL_UUID)
            }
        }
    })

private fun javaVersion(
    version: String,
    distribution: Distribution,
): Version =
    Version(
        candidate = "java",
        version = version,
        platform = Platform.LINUX_X64,
        url = "https://example.com/java-$version-${distribution.name.lowercase()}.tar.gz",
        visible = true.some(),
        distribution = distribution.some(),
    )

private fun liberica(version: String): Version = javaVersion(version, Distribution.LIBERICA)

private fun temurin(version: String): Version = javaVersion(version, Distribution.TEMURIN)

private fun kona(version: String): Version = javaVersion(version, Distribution.KONA)

private fun gradle(version: String): Version =
    Version(
        candidate = "gradle",
        version = version,
        platform = Platform.UNIVERSAL,
        url = "https://example.com/gradle-$version.zip",
        visible = true.some(),
        distribution = none(),
    )

private suspend fun ApplicationTestBuilder.postVersion(version: Version): HttpResponse =
    client.post("/versions") {
        contentType(ContentType.Application.Json)
        setBody(version.toJsonString())
        bearerAuth(JwtTestSupport.adminToken())
    }

private fun visibilityOf(version: Version): Option<Boolean> =
    selectVersion(
        candidate = version.candidate,
        version = version.version,
        distribution = version.distribution,
        platform = version.platform,
    ).flatMap { it.visible }
