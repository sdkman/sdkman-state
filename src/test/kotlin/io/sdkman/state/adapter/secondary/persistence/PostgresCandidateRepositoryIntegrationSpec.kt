package io.sdkman.state.adapter.secondary.persistence

import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.CandidateRegistration
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertVersions
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresCandidateRepositoryIntegrationSpec :
    ShouldSpec({
        val repo = PostgresCandidateRepository()

        fun registrationOf(
            candidate: String,
            name: String = candidate.replaceFirstChar { it.uppercase() },
        ) = CandidateRegistration(
            candidate = candidate,
            name = name,
            description = "The $name candidate.",
            websiteUrl = "https://$candidate.example.com",
        )

        should("report a newly registered candidate as created") {
            withCleanDatabase {
                val result = repo.upsert(registrationOf("kotlin"))

                val (_, created) = result.shouldBeRight()
                created shouldBe true
            }
        }

        should("report a re-registered candidate as not created") {
            withCleanDatabase {
                repo.upsert(registrationOf("kotlin"))

                val result = repo.upsert(registrationOf("kotlin", name = "Kotlin Language"))

                val (_, created) = result.shouldBeRight()
                created shouldBe false
            }
        }

        should("update the name on a re-registration") {
            withCleanDatabase {
                repo.upsert(registrationOf("kotlin", name = "Kotlin"))

                val (candidate, _) = repo.upsert(registrationOf("kotlin", name = "Kotlin Language")).shouldBeRight()

                candidate.name shouldBe "Kotlin Language"
            }
        }

        should("preserve created_at across a re-registration") {
            withCleanDatabase {
                val (first, _) = repo.upsert(registrationOf("kotlin")).shouldBeRight()

                val (second, _) = repo.upsert(registrationOf("kotlin", name = "Kotlin Language")).shouldBeRight()

                second.createdAt shouldBe first.createdAt
            }
        }

        should("find a registered candidate by identifier") {
            withCleanDatabase {
                repo.upsert(registrationOf("groovy"))

                val result = repo.find("groovy")

                result.shouldBeRight().shouldBeSome().candidate shouldBe "groovy"
            }
        }

        should("return none() for an unregistered candidate") {
            withCleanDatabase {
                val result = repo.find("unregistered")

                result.shouldBeRight().shouldBeNone()
            }
        }

        should("return all candidates ascending by identifier") {
            withCleanDatabase {
                repo.upsert(registrationOf("scala"))
                repo.upsert(registrationOf("groovy"))
                repo.upsert(registrationOf("kotlin"))

                val result = repo.findAll()

                result.shouldBeRight().map { it.candidate } shouldBe listOf("groovy", "kotlin", "scala")
            }
        }

        should("return the removed row on delete") {
            withCleanDatabase {
                repo.upsert(registrationOf("groovy", name = "Groovy"))

                val result = repo.delete("groovy")

                result.shouldBeRight().shouldBeSome().name shouldBe "Groovy"
            }
        }

        should("not find a candidate after it is deleted") {
            withCleanDatabase {
                repo.upsert(registrationOf("groovy"))
                repo.delete("groovy")

                val result = repo.find("groovy")

                result.shouldBeRight().shouldBeNone()
            }
        }

        should("return none() when deleting an unregistered candidate") {
            withCleanDatabase {
                val result = repo.delete("unregistered")

                result.shouldBeRight().shouldBeNone()
            }
        }

        should("count only the versions of the named candidate") {
            withCleanDatabase {
                insertVersions(
                    Version(candidate = "kotlin", version = "2.0.0", platform = Platform.LINUX_X64, url = "https://kotlin-2.0.0"),
                    Version(candidate = "kotlin", version = "2.0.1", platform = Platform.LINUX_X64, url = "https://kotlin-2.0.1"),
                    Version(candidate = "groovy", version = "4.0.0", platform = Platform.UNIVERSAL, url = "https://groovy-4.0.0"),
                )

                val result = repo.countVersions("kotlin")

                result shouldBeRight 2L
            }
        }

        should("count invisible versions") {
            withCleanDatabase {
                insertVersions(
                    Version(
                        candidate = "kotlin",
                        version = "2.0.0",
                        platform = Platform.LINUX_X64,
                        url = "https://kotlin-2.0.0",
                        visible = false.some(),
                    ),
                )

                val result = repo.countVersions("kotlin")

                result shouldBeRight 1L
            }
        }

        should("count zero for a candidate with no versions") {
            withCleanDatabase {
                repo.upsert(registrationOf("kotlin"))

                val result = repo.countVersions("kotlin")

                result shouldBeRight 0L
            }
        }
    })
