package io.sdkman.state.adapter.secondary.persistence

import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.CandidateDefault
import io.sdkman.state.domain.model.CandidateDeletion
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertTag
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresCandidateRepositoryIntegrationSpec :
    ShouldSpec({

        val repo = PostgresCandidateRepository()

        fun registration(
            candidate: String,
            name: String = candidate.replaceFirstChar { it.uppercase() },
        ) = CandidateRegistration(
            candidate = candidate,
            name = name,
            description = "The $name build tool.",
            websiteUrl = "https://$candidate.example.com/",
        )

        // R2: the upsert answers created/updated from the same statement, so the caller can
        // tell a 201 from a 200 without a prior read.
        should("report a new candidate as created") {
            withCleanDatabase {
                // when
                val result = repo.upsert(registration("jbang"))

                // then
                val (candidate, created) = result.shouldBeRight()
                created shouldBe true
                candidate.candidate shouldBe "jbang"
                candidate.name shouldBe "Jbang"
                candidate.websiteUrl shouldBe "https://jbang.example.com/"
            }
        }

        // R2: a second registration rewrites the metadata. The identifier is the conflict target
        // and is never in the SET clause, so the row is updated rather than duplicated.
        should("report a repeated candidate as updated and rewrite its name") {
            withCleanDatabase {
                // given
                repo.upsert(registration("jbang", name = "JBang")).shouldBeRight()

                // when
                val result = repo.upsert(registration("jbang", name = "JBang Renamed"))

                // then
                val (candidate, created) = result.shouldBeRight()
                created shouldBe false
                candidate.name shouldBe "JBang Renamed"
                repo.findAll().shouldBeRight().map { it.candidate } shouldBe listOf("jbang")
            }
        }

        // R11: ascending by identifier is part of the GET /candidates contract, so it is asserted
        // here rather than left to the query planner.
        should("return all candidates ascending by identifier") {
            withCleanDatabase {
                // given
                repo.upsert(registration("scala")).shouldBeRight()
                repo.upsert(registration("ant")).shouldBeRight()
                repo.upsert(registration("gradle")).shouldBeRight()

                // when
                val result = repo.findAll()

                // then
                result.shouldBeRight().map { it.candidate } shouldBe listOf("ant", "gradle", "scala")
            }
        }

        // R6: the filter reads `versions.distribution`, which is what the tag lookup behind
        // GET /versions/{c}/tags/lts reads. A distributed row carries no candidate default.
        should("drop a default whose version carries a distribution") {
            withCleanDatabase {
                // given: a distributed java row and a plain gradle row, both tagged lts
                val javaId =
                    insertVersionWithId(
                        Version(
                            candidate = "java",
                            version = "21.0.8-tem",
                            platform = Platform.UNIVERSAL,
                            url = "https://java-21.0.8-tem",
                            distribution = Distribution.TEMURIN.some(),
                        ),
                    )
                insertTag("java", "lts", Distribution.TEMURIN.some(), Platform.UNIVERSAL, javaId)

                val gradleId =
                    insertVersionWithId(
                        Version(
                            candidate = "gradle",
                            version = "8.14.3",
                            platform = Platform.UNIVERSAL,
                            url = "https://gradle-8.14.3",
                        ),
                    )
                insertTag("gradle", "lts", none(), Platform.UNIVERSAL, gradleId)

                // when
                val result = repo.findDefaults()

                // then
                result.shouldBeRight() shouldBe
                    listOf(CandidateDefault(candidate = "gradle", platform = Platform.UNIVERSAL, version = "8.14.3"))
            }
        }

        // R5: resolution is restricted to UNIVERSAL and LINUX_X64, not merely ordered by them.
        should("drop a default tagged only on an unconsulted platform") {
            withCleanDatabase {
                // given
                val versionId =
                    insertVersionWithId(
                        Version(
                            candidate = "scala",
                            version = "3.7.1",
                            platform = Platform.MAC_ARM64,
                            url = "https://scala-3.7.1",
                        ),
                    )
                insertTag("scala", "lts", none(), Platform.MAC_ARM64, versionId)

                // when
                val result = repo.findDefaults()

                // then
                result.shouldBeRight().shouldBeEmpty()
            }
        }

        // R3: the count is the mechanism behind the 409 -- there is no foreign key behind it
        // (docs/decisions/0008), so it carries its own test.
        should("refuse to delete a candidate that still owns versions") {
            withCleanDatabase {
                // given
                repo.upsert(registration("gradle")).shouldBeRight()
                insertVersionWithId(
                    Version(
                        candidate = "gradle",
                        version = "8.14.3",
                        platform = Platform.UNIVERSAL,
                        url = "https://gradle-8.14.3",
                    ),
                )

                // when
                val result = repo.delete("gradle")

                // then
                result.shouldBeRight() shouldBe CandidateDeletion.HasVersions(1)
                repo.findAll().shouldBeRight().map { it.candidate } shouldBe listOf("gradle")
            }
        }

        should("report an unknown candidate as not found on delete") {
            withCleanDatabase {
                // when
                val result = repo.delete("nonesuch")

                // then
                result.shouldBeRight() shouldBe CandidateDeletion.NotFound
            }
        }
    })
