package io.sdkman.state.acceptance

import arrow.core.Option
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.JwtTestSupport
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.selectVersion
import io.sdkman.state.support.toJson
import io.sdkman.state.support.toJsonString
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Covers the core supersession rule and the series boundaries: publishing a visible,
 * eligible `java` version retires every other row of its release series — same
 * candidate, distribution, platform, major line and variant — and nothing outside it.
 *
 * Guards, tags, audit and concurrency live in their own specs.
 */
@Tags("acceptance")
class JavaVersionSupersessionAcceptanceSpec :
    ShouldSpec({

        should("retire the migrated counterpart when its semverish rebuild is published") {
            val migrated = liberica("26.0.2", Platform.LINUX_X64)
            val republished = liberica("26.0.2+1.1", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(migrated)
                withTestApplication { postVersion(republished).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(migrated) shouldBe false.some()
            }
        }

        should("keep the published version visible when it supersedes its series") {
            val migrated = liberica("26.0.2", Platform.LINUX_X64)
            val republished = liberica("26.0.2+1.1", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(migrated)
                withTestApplication { postVersion(republished).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(republished) shouldBe true.some()
            }
        }

        should("retire an older build in the same major line") {
            val previous = liberica("17.0.19-crac", Platform.LINUX_X64)
            val current = liberica("17.0.20-crac+1.2", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(previous)
                withTestApplication { postVersion(current).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(previous) shouldBe false.some()
            }
        }

        should("retire the whole major series and not only an exact version match") {
            val kona = { version: String ->
                Version(
                    candidate = "java",
                    version = version,
                    platform = Platform.LINUX_X64,
                    url = "https://example.com/java-$version-kona.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.KONA.some(),
                )
            }
            val published = kona("25.0.4+1")

            withCleanDatabase {
                insertVersions(kona("25.0.1+1"), kona("25.0.2+1"), kona("25.0.3+1"))
                withTestApplication {
                    postVersion(published).status shouldBe HttpStatusCode.NoContent

                    client.get("/versions/java?platform=LINUX_X64&distribution=KONA&visible=true").apply {
                        Json.decodeFromString<JsonArray>(bodyAsText()) shouldBe
                            JsonArray(listOf(published.copy(tags = emptyList<String>().some()).toJson()))
                    }
                }
            }
        }

        should("leave the fx variant alone when a plain build is published") {
            val fxVariant = liberica("26.0.2-fx+1.1", Platform.LINUX_X64)
            val plain = liberica("26.0.2+1.1", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(fxVariant)
                withTestApplication { postVersion(plain).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(fxVariant) shouldBe true.some()
            }
        }

        should("leave the same version on another platform alone") {
            val otherPlatform = liberica("26.0.2", Platform.MAC_ARM64)
            val published = liberica("26.0.2+1.1", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(liberica("26.0.2", Platform.LINUX_X64), otherPlatform)
                withTestApplication { postVersion(published).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(otherPlatform) shouldBe true.some()
            }
        }

        should("leave another distribution alone") {
            val zulu =
                Version(
                    candidate = "java",
                    version = "26.0.2",
                    platform = Platform.LINUX_X64,
                    url = "https://example.com/java-26.0.2-zulu.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.ZULU.some(),
                )

            withCleanDatabase {
                insertVersions(zulu)
                withTestApplication {
                    postVersion(liberica("26.0.2+1.1", Platform.LINUX_X64)).status shouldBe HttpStatusCode.NoContent
                }

                visibilityOf(zulu) shouldBe true.some()
            }
        }

        should("leave another major line alone") {
            val openjdk = { version: String ->
                Version(
                    candidate = "java",
                    version = version,
                    platform = Platform.LINUX_X64,
                    url = "https://example.com/java-$version-open.tar.gz",
                    visible = true.some(),
                    distribution = Distribution.OPENJDK.some(),
                )
            }
            val previousMajor = openjdk("21.0.2")

            withCleanDatabase {
                insertVersions(previousMajor)
                withTestApplication { postVersion(openjdk("26.0.2+1.1")).status shouldBe HttpStatusCode.NoContent }

                visibilityOf(previousMajor) shouldBe true.some()
            }
        }

        should("resolve a retired version by its explicit identifier") {
            val migrated = liberica("26.0.2", Platform.LINUX_X64)

            withCleanDatabase {
                insertVersions(migrated)
                withTestApplication {
                    postVersion(liberica("26.0.2+1.1", Platform.LINUX_X64)).status shouldBe HttpStatusCode.NoContent

                    client.get("/versions/java/26.0.2?platform=LINUX_X64&distribution=LIBERICA").apply {
                        Json.decodeFromString<JsonObject>(bodyAsText()) shouldBe
                            migrated.copy(visible = false.some(), tags = emptyList<String>().some()).toJson()
                    }
                }
            }
        }
    })

private fun liberica(
    version: String,
    platform: Platform,
): Version =
    Version(
        candidate = "java",
        version = version,
        platform = platform,
        url = "https://example.com/java-$version-librca.tar.gz",
        visible = true.some(),
        distribution = Distribution.LIBERICA.some(),
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
