package io.sdkman.state.adapter.secondary.persistence

import arrow.core.Option
import arrow.core.none
import arrow.core.some
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.UniqueTag
import io.sdkman.state.domain.model.UniqueVersion
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.insertVersionWithId
import io.sdkman.state.support.selectTagNames
import io.sdkman.state.support.shouldBeRight
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.withCleanDatabase

@Tags("integration")
class PostgresTagRepositoryIntegrationSpec :
    ShouldSpec({

        val repo = PostgresTagRepository()

        suspend fun insertTagged(
            version: String,
            platform: Platform,
            tags: List<String>,
            distribution: Option<Distribution> = none(),
        ): Int {
            val versionId =
                insertVersionWithId(
                    Version(
                        candidate = "jpx",
                        version = version,
                        platform = platform,
                        url = "https://jpx-$version-$platform",
                        visible = true.some(),
                        distribution = distribution,
                    ),
                )
            repo.replaceTags(versionId, "jpx", distribution, platform, tags).shouldBeRight()
            return versionId
        }

        context("replaceTags") {
            should("create tags for a version") {
                withCleanDatabase {
                    // given: a version exists
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )

                    // when: tags are assigned
                    val result =
                        repo.replaceTags(
                            versionId = versionId,
                            candidate = "java",
                            distribution = Distribution.TEMURIN.some(),
                            platform = Platform.LINUX_X64,
                            tags = listOf("latest", "27"),
                        )

                    // then: tags are stored
                    result.shouldBeRight()
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest", "27")
                }
            }

            should("replace tags declaratively") {
                withCleanDatabase {
                    // given: a version with tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27", "lts"))

                    // when: tags are replaced
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27"))

                    // then: only the new tags remain
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest", "27")
                }
            }

            should("enforce mutual exclusivity when reassigning a tag") {
                withCleanDatabase {
                    // given: two versions, first tagged as latest
                    val versionId1 =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.1",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.1",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    val versionId2 =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId1, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest"))

                    // when: the tag is assigned to a different version
                    repo.replaceTags(versionId2, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest"))

                    // then: the first version no longer has the tag
                    selectTagNames(versionId1).shouldBeEmpty()
                    selectTagNames(versionId2) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }

            should("clear all tags when given an empty list") {
                withCleanDatabase {
                    // given: a version with tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27"))

                    // when: replaced with empty list
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, emptyList())

                    // then: no tags remain
                    selectTagNames(versionId).shouldBeEmpty()
                }
            }
        }

        context("assignTag") {
            should("assign a previously-unassigned tag to a version") {
                withCleanDatabase {
                    // given: a version with no tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )

                    // when: a tag is assigned
                    val result =
                        repo.assignTag(
                            versionId = versionId,
                            candidate = "java",
                            distribution = Distribution.TEMURIN.some(),
                            platform = Platform.LINUX_X64,
                            tag = "latest",
                        )

                    // then: the tag row exists pointing at the target version
                    result.shouldBeRight()
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }

            should("move a tag to a different version, removing it from the original") {
                withCleanDatabase {
                    // given: two versions, the first holding the tag (mutual exclusivity scope)
                    val versionId1 =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.1",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.1",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    val versionId2 =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.assignTag(versionId1, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest").shouldBeRight()

                    // when: the same tag is assigned to a different version in the same scope
                    val result = repo.assignTag(versionId2, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")

                    // then: the tag re-points to the new version and the original no longer holds it
                    result.shouldBeRight()
                    selectTagNames(versionId1).shouldBeEmpty()
                    selectTagNames(versionId2) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }

            should("be an idempotent no-op when re-assigning a tag the version already holds") {
                withCleanDatabase {
                    // given: a version that already holds the tag
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.assignTag(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest").shouldBeRight()

                    // when: the same tag is assigned again
                    val result = repo.assignTag(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")

                    // then: the operation succeeds and nothing changes
                    result.shouldBeRight()
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }

            should("preserve the version's existing tags when assigning a new tag") {
                withCleanDatabase {
                    // given: a version that already holds a tag
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.assignTag(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "27").shouldBeRight()

                    // when: a different tag is assigned to the same version
                    val result = repo.assignTag(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, "latest")

                    // then: both the existing and the new tag are present (append, not replace)
                    result.shouldBeRight()
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("27", "latest")
                }
            }
        }

        context("findTagsByVersionId") {
            should("return all tags for a version") {
                withCleanDatabase {
                    // given: a version with tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27"))

                    // when: querying tags by version ID
                    val tags = repo.findTagsByVersionId(versionId).shouldBeRight()

                    // then: correct tags returned
                    tags shouldHaveSize 2
                    tags.map { it.tag } shouldContainExactlyInAnyOrder listOf("latest", "27")
                    tags.forEach {
                        it.versionId shouldBe versionId
                        it.candidate shouldBe "java"
                        it.distribution shouldBe Distribution.TEMURIN.some()
                        it.platform shouldBe Platform.LINUX_X64
                    }
                }
            }

            should("return empty list when version has no tags") {
                withCleanDatabase {
                    // given: a version without tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )

                    // when: querying tags
                    val tags = repo.findTagsByVersionId(versionId).shouldBeRight()

                    // then: empty list returned
                    tags.shouldBeEmpty()
                }
            }
        }

        context("deleteTag") {
            should("remove a tag by its unique scope") {
                withCleanDatabase {
                    // given: a tagged version
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27"))

                    // when: deleting one tag
                    val deletedCount =
                        repo
                            .deleteTag(
                                UniqueTag(
                                    candidate = "java",
                                    tag = "latest",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                ),
                            ).shouldBeRight()

                    // then: only that tag is removed
                    deletedCount shouldBe 1
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("27")
                }
            }

            should("return 0 when deleting a non-existent tag") {
                withCleanDatabase {
                    // when: deleting a tag that does not exist
                    val deletedCount =
                        repo
                            .deleteTag(
                                UniqueTag(
                                    candidate = "java",
                                    tag = "nonexistent",
                                    distribution = Distribution.TEMURIN.some(),
                                    platform = Platform.LINUX_X64,
                                ),
                            ).shouldBeRight()

                    // then: 0 rows affected
                    deletedCount shouldBe 0
                }
            }
        }

        context("clearStableAcrossShapes") {
            should("clear stable from another version's platform rows when a UNIVERSAL row takes it") {
                withCleanDatabase {
                    // given: an older version stable on two platforms
                    val linuxId = insertTagged("1.1.0", Platform.LINUX_X64, listOf("stable"))
                    val macId = insertTagged("1.1.0", Platform.MAC_ARM64, listOf("stable"))
                    insertTagged("1.2.0", Platform.UNIVERSAL, listOf("stable"))

                    // when: stable is cleared across shapes for the UNIVERSAL row
                    val result = repo.clearStableAcrossShapes(UniqueVersion("jpx", "1.2.0", none(), Platform.UNIVERSAL))

                    // then: both platform stables are gone
                    result.shouldBeRight() shouldBe 2
                    selectTagNames(linuxId).shouldBeEmpty()
                    selectTagNames(macId).shouldBeEmpty()
                }
            }

            should("clear stable from another version's UNIVERSAL row when a platform row takes it") {
                withCleanDatabase {
                    // given: an older version stable on UNIVERSAL
                    val universalId = insertTagged("1.1.0", Platform.UNIVERSAL, listOf("stable"))
                    val linuxId = insertTagged("1.2.0", Platform.LINUX_X64, listOf("stable"))

                    // when: stable is cleared across shapes for the platform row
                    val result = repo.clearStableAcrossShapes(UniqueVersion("jpx", "1.2.0", none(), Platform.LINUX_X64))

                    // then: the UNIVERSAL stable is gone and the platform stable stays
                    result.shouldBeRight() shouldBe 1
                    selectTagNames(universalId).shouldBeEmpty()
                    selectTagNames(linuxId) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }

            should("spare the version's own rows of the other shape") {
                withCleanDatabase {
                    // given: one version stable on UNIVERSAL and on LINUX_X64
                    val universalId = insertTagged("1.2.0", Platform.UNIVERSAL, listOf("stable"))
                    val linuxId = insertTagged("1.2.0", Platform.LINUX_X64, listOf("stable"))

                    // when: stable is cleared across shapes for its UNIVERSAL row
                    val result = repo.clearStableAcrossShapes(UniqueVersion("jpx", "1.2.0", none(), Platform.UNIVERSAL))

                    // then: nothing is cleared
                    result.shouldBeRight() shouldBe 0
                    selectTagNames(universalId) shouldContainExactlyInAnyOrder listOf("stable")
                    selectTagNames(linuxId) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }

            should("leave the stable of another distribution in place") {
                withCleanDatabase {
                    // given: an older version stable on LINUX_X64 in another distribution
                    val otherDistributionId =
                        insertTagged("1.1.0", Platform.LINUX_X64, listOf("stable"), Distribution.TEMURIN.some())
                    insertTagged("1.2.0", Platform.UNIVERSAL, listOf("stable"))

                    // when: stable is cleared across shapes for the UNIVERSAL row without a distribution
                    val result = repo.clearStableAcrossShapes(UniqueVersion("jpx", "1.2.0", none(), Platform.UNIVERSAL))

                    // then: the other distribution keeps its stable
                    result.shouldBeRight() shouldBe 0
                    selectTagNames(otherDistributionId) shouldContainExactlyInAnyOrder listOf("stable")
                }
            }

            should("leave latest on the other shape in place") {
                withCleanDatabase {
                    // given: an older version tagged stable and latest on LINUX_X64
                    val linuxId = insertTagged("1.1.0", Platform.LINUX_X64, listOf("stable", "latest"))
                    insertTagged("1.2.0", Platform.UNIVERSAL, listOf("stable"))

                    // when: stable is cleared across shapes for the UNIVERSAL row
                    repo.clearStableAcrossShapes(UniqueVersion("jpx", "1.2.0", none(), Platform.UNIVERSAL)).shouldBeRight()

                    // then: only latest remains
                    selectTagNames(linuxId) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }
        }

        context("hasTagsForVersion") {
            should("return true when a version has tags") {
                withCleanDatabase {
                    // given: a tagged version
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest"))

                    // when/then
                    val hasTags = repo.hasTagsForVersion(versionId).shouldBeRight()
                    hasTags shouldBe true
                }
            }

            should("return false when a version has no tags") {
                withCleanDatabase {
                    // given: a version without tags
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )

                    // when/then
                    val hasTags = repo.hasTagsForVersion(versionId).shouldBeRight()
                    hasTags shouldBe false
                }
            }
        }

        context("findTagNamesByVersionId") {
            should("return tag names for a version") {
                withCleanDatabase {
                    // given: a tagged version
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "java",
                                version = "27.0.2",
                                platform = Platform.LINUX_X64,
                                url = "https://java-27.0.2",
                                visible = true.some(),
                                distribution = Distribution.TEMURIN.some(),
                            ),
                        )
                    repo.replaceTags(versionId, "java", Distribution.TEMURIN.some(), Platform.LINUX_X64, listOf("latest", "27", "27.0"))

                    // when: querying tag names
                    val tagNames = repo.findTagNamesByVersionId(versionId).shouldBeRight()

                    // then: correct names returned
                    tagNames shouldContainExactlyInAnyOrder listOf("latest", "27", "27.0")
                }
            }
        }

        context("NA distribution handling") {
            should("store and retrieve tags for candidates without distributions") {
                withCleanDatabase {
                    // given: a universal version without distribution (e.g., Gradle)
                    val versionId =
                        insertVersionWithId(
                            Version(
                                candidate = "gradle",
                                version = "8.12",
                                platform = Platform.UNIVERSAL,
                                url = "https://gradle-8.12",
                                visible = true.some(),
                                distribution = none(),
                            ),
                        )

                    // when: tags are assigned with none() distribution
                    repo.replaceTags(versionId, "gradle", none(), Platform.UNIVERSAL, listOf("latest", "8"))

                    // then: tags are stored and retrievable
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest", "8")

                    val versionRepo = PostgresVersionRepository()
                    val resolved =
                        versionRepo
                            .findByTag(
                                "gradle",
                                "latest",
                                Platform.UNIVERSAL,
                                none(),
                            ).shouldBeRight()
                    resolved.shouldBeSome()
                    resolved.onSome { v ->
                        v.candidate shouldBe "gradle"
                        v.version shouldBe "8.12"
                    }
                }
            }

            should("enforce mutual exclusivity with NA distribution") {
                withCleanDatabase {
                    // given: two versions without distribution
                    val versionId1 =
                        insertVersionWithId(
                            Version(
                                candidate = "gradle",
                                version = "8.12",
                                platform = Platform.UNIVERSAL,
                                url = "https://gradle-8.12",
                                visible = true.some(),
                                distribution = none(),
                            ),
                        )
                    val versionId2 =
                        insertVersionWithId(
                            Version(
                                candidate = "gradle",
                                version = "8.13",
                                platform = Platform.UNIVERSAL,
                                url = "https://gradle-8.13",
                                visible = true.some(),
                                distribution = none(),
                            ),
                        )
                    repo.replaceTags(versionId1, "gradle", none(), Platform.UNIVERSAL, listOf("latest"))

                    // when: reassigning the tag to a new version
                    repo.replaceTags(versionId2, "gradle", none(), Platform.UNIVERSAL, listOf("latest"))

                    // then: tag moved to the new version
                    selectTagNames(versionId1).shouldBeEmpty()
                    selectTagNames(versionId2) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }
        }
    })
