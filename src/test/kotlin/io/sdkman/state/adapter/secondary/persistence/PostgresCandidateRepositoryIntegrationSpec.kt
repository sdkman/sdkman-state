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
                // when: a candidate that does not exist yet is registered
                val result = repo.upsert(registrationOf("kotlin"))

                // then: the write reports itself as an insert, so the route can answer 201
                val (_, created) = result.shouldBeRight()
                created shouldBe true
            }
        }

        should("report a re-registered candidate as not created") {
            withCleanDatabase {
                // given: the candidate is already registered
                repo.upsert(registrationOf("kotlin"))

                // when: the same identifier is posted again
                val result = repo.upsert(registrationOf("kotlin", name = "Kotlin Language"))

                // then: the write reports itself as an update, so the route can answer 200
                val (_, created) = result.shouldBeRight()
                created shouldBe false
            }
        }

        should("update the name on a re-registration") {
            withCleanDatabase {
                // given: the candidate is registered under its original name
                repo.upsert(registrationOf("kotlin", name = "Kotlin"))

                // when: the same identifier is posted with a new name (business rule 2)
                val (candidate, _) = repo.upsert(registrationOf("kotlin", name = "Kotlin Language")).shouldBeRight()

                // then: the mutable metadata carries the new value
                candidate.name shouldBe "Kotlin Language"
            }
        }

        should("preserve created_at across a re-registration") {
            withCleanDatabase {
                // given: the candidate is registered once
                val (first, _) = repo.upsert(registrationOf("kotlin")).shouldBeRight()

                // when: the same identifier is posted again
                val (second, _) = repo.upsert(registrationOf("kotlin", name = "Kotlin Language")).shouldBeRight()

                // then: registration age survives the update — only last_updated_at moves
                second.createdAt shouldBe first.createdAt
            }
        }

        should("find a registered candidate by identifier") {
            withCleanDatabase {
                // given: a registered candidate
                repo.upsert(registrationOf("groovy"))

                // when: it is read back by identifier
                val result = repo.find("groovy")

                // then: the row is returned
                result.shouldBeRight().shouldBeSome().candidate shouldBe "groovy"
            }
        }

        should("return none() for an unregistered candidate") {
            withCleanDatabase {
                // when: an identifier that was never registered is read
                val result = repo.find("unregistered")

                // then: absence is reported as none(), not as a failure
                result.shouldBeRight().shouldBeNone()
            }
        }

        should("return all candidates ascending by identifier") {
            withCleanDatabase {
                // given: three candidates registered out of order
                repo.upsert(registrationOf("scala"))
                repo.upsert(registrationOf("groovy"))
                repo.upsert(registrationOf("kotlin"))

                // when: the whole registry is listed
                val result = repo.findAll()

                // then: the order is ascending by identifier (business rule 11), not insertion order
                result.shouldBeRight().map { it.candidate } shouldBe listOf("groovy", "kotlin", "scala")
            }
        }

        should("return the removed row on delete") {
            withCleanDatabase {
                // given: a registered candidate
                repo.upsert(registrationOf("groovy", name = "Groovy"))

                // when: it is deleted
                val result = repo.delete("groovy")

                // then: the caller learns what it removed, without a preceding read
                result.shouldBeRight().shouldBeSome().name shouldBe "Groovy"
            }
        }

        should("not find a candidate after it is deleted") {
            withCleanDatabase {
                // given: a registered candidate that is then deleted
                repo.upsert(registrationOf("groovy"))
                repo.delete("groovy")

                // when: it is read back
                val result = repo.find("groovy")

                // then: deletion is hard — nothing remains to read
                result.shouldBeRight().shouldBeNone()
            }
        }

        should("return none() when deleting an unregistered candidate") {
            withCleanDatabase {
                // when: an identifier that was never registered is deleted
                val result = repo.delete("unregistered")

                // then: absence is reported as none(), so the route can answer 404
                result.shouldBeRight().shouldBeNone()
            }
        }

        should("count only the versions of the named candidate") {
            withCleanDatabase {
                // given: versions published under two different candidates
                insertVersions(
                    Version(candidate = "kotlin", version = "2.0.0", platform = Platform.LINUX_X64, url = "https://kotlin-2.0.0"),
                    Version(candidate = "kotlin", version = "2.0.1", platform = Platform.LINUX_X64, url = "https://kotlin-2.0.1"),
                    Version(candidate = "groovy", version = "4.0.0", platform = Platform.UNIVERSAL, url = "https://groovy-4.0.0"),
                )

                // when: one candidate's versions are counted
                val result = repo.countVersions("kotlin")

                // then: the count behind the delete guard ignores every other candidate
                result shouldBeRight 2L
            }
        }

        should("count invisible versions") {
            withCleanDatabase {
                // given: the only version of the candidate is hidden from listings
                insertVersions(
                    Version(
                        candidate = "kotlin",
                        version = "2.0.0",
                        platform = Platform.LINUX_X64,
                        url = "https://kotlin-2.0.0",
                        visible = false.some(),
                    ),
                )

                // when: the candidate's versions are counted
                val result = repo.countVersions("kotlin")

                // then: the guard asks whether anything was ever published (business rule 3)
                result shouldBeRight 1L
            }
        }

        should("count zero for a candidate with no versions") {
            withCleanDatabase {
                // given: a registered candidate that never published
                repo.upsert(registrationOf("kotlin"))

                // when: its versions are counted
                val result = repo.countVersions("kotlin")

                // then: the delete guard lets it through
                result shouldBeRight 0L
            }
        }
    })
