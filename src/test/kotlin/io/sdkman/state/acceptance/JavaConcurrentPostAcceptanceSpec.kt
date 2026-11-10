package io.sdkman.state.acceptance

import arrow.core.some
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.sdkman.state.adapter.secondary.persistence.VersionsTable
import io.sdkman.state.adapter.secondary.persistence.dbQuery
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.support.javaTagHolder
import io.sdkman.state.support.javaVendorToken
import io.sdkman.state.support.javaVersion
import io.sdkman.state.support.postJavaVersion
import io.sdkman.state.support.registerCandidates
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.tagsOf
import io.sdkman.state.support.visibilityOf
import io.sdkman.state.support.withCleanDatabase
import io.sdkman.state.support.withTestApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

@Tags("acceptance")
class JavaConcurrentPostAcceptanceSpec :
    ShouldSpec({

        should("leave only the highest build visible, holding 25 and latest, when a series is posted concurrently") {
            val concurrentRequests = 20
            val series = (1..concurrentRequests).map { build -> javaVersion(Distribution.TEMURIN, "25.0.$build.0+1") }
            val highestBuild = series.last()

            withCleanDatabase {
                // when: DISCO posts twenty builds of series 25 at once
                withTestApplication {
                    registerCandidates("java")

                    val disco = javaVendorToken()
                    val statuses =
                        coroutineScope {
                            series
                                .map { version -> async { postJavaVersion(version, disco).status } }
                                .awaitAll()
                        }

                    withClue("every concurrent POST /versions/java should return 204 NoContent") {
                        statuses.toSet() shouldBe setOf(HttpStatusCode.NoContent)
                    }
                }

                // then: the scope holds one visible row, the highest build, and it holds the moved tags
                val visibleRows =
                    dbQuery {
                        VersionsTable
                            .selectAll()
                            .where {
                                (VersionsTable.candidate eq "java") and
                                    (VersionsTable.distribution eq Distribution.TEMURIN.name) and
                                    (VersionsTable.platform eq Platform.LINUX_X64.name) and
                                    (VersionsTable.visible eq true)
                            }.count()
                    }

                withClue("$concurrentRequests concurrent posts into one series must leave one visible member") {
                    visibleRows shouldBe 1L
                }
                withClue("the one visible member must be the highest build ${highestBuild.version}") {
                    visibilityOf(highestBuild) shouldBe true.some()
                }
                withClue("no tag move may be lost: the head must hold 25 and latest") {
                    tagsOf(highestBuild) shouldContainAll listOf("25", "latest")
                }
            }
        }

        should("place lts and latest across the distribution when its platforms are posted concurrently") {
            val sharedPlatforms = listOf(Platform.LINUX_X64, Platform.MAC_ARM64)
            val sharedBuilds = (1..5).map { build -> "25.0.$build.0+1" } + "27.0.1.0+9"
            val earlyAccessBuilds =
                (1..5).map { build -> javaVersion(Distribution.TEMURIN, "28.0.0.0+ea.$build", Platform.LINUX_X64) }
            val posts =
                sharedPlatforms.flatMap { platform ->
                    sharedBuilds.map { version -> javaVersion(Distribution.TEMURIN, version, platform) }
                } + earlyAccessBuilds

            withCleanDatabase {
                // when: DISCO posts 25 and 27 builds on two platforms, and 28 early access on one, all at once
                withTestApplication {
                    registerCandidates("java")

                    val disco = javaVendorToken()
                    val statuses =
                        coroutineScope {
                            posts
                                .map { version -> async { postJavaVersion(version, disco).status } }
                                .awaitAll()
                        }

                    withClue("every concurrent POST /versions/java should return 204 NoContent") {
                        statuses.toSet() shouldBe setOf(HttpStatusCode.NoContent)
                    }
                }

                // then: latest follows the top feature 28, which only LINUX_X64 has, and lts sits on the 25 head of both
                withClue("latest on LINUX_X64 must be the newest 28 early access") {
                    javaTagHolder("latest", Distribution.TEMURIN, Platform.LINUX_X64) shouldBeSome "28.0.0.0+ea.5"
                }
                withClue("MAC_ARM64 has no 28, so no row there may hold latest") {
                    javaTagHolder("latest", Distribution.TEMURIN, Platform.MAC_ARM64).shouldBeNone()
                }
                sharedPlatforms.forEach { platform ->
                    withClue("lts on $platform must be the newest 25 build") {
                        javaTagHolder("lts", Distribution.TEMURIN, platform) shouldBeSome "25.0.5.0+1"
                    }
                }
            }
        }
    })
