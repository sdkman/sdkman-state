package io.sdkman.repos

import arrow.core.None
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.sdkman.domain.Distribution
import io.sdkman.domain.Platform
import io.sdkman.domain.UniqueTag
import io.sdkman.domain.Version
import io.sdkman.support.insertVersionWithId
import io.sdkman.support.selectTagNames
import io.sdkman.support.withCleanDatabase

class TagsRepositorySpec :
    ShouldSpec({

        val repo = TagsRepositoryImpl()

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
                    result.isRight() shouldBe true
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
                    val result = repo.findTagsByVersionId(versionId)

                    // then: correct tags returned
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldHaveSize 2
                    result.getOrNull()!!.map { it.tag } shouldContainExactlyInAnyOrder listOf("latest", "27")
                    result.getOrNull()!!.forEach {
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
                    val result = repo.findTagsByVersionId(versionId)

                    // then: empty list returned
                    result.isRight() shouldBe true
                    result.getOrNull()!!.shouldBeEmpty()
                }
            }
        }

        context("findVersionIdByTag") {
            should("resolve a tag to the correct version ID") {
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

                    // when: resolving the tag
                    val result = repo.findVersionIdByTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64)

                    // then: correct version ID returned
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe versionId.some()
                }
            }

            should("return None for a non-existent tag") {
                withCleanDatabase {
                    // when: resolving a tag that does not exist
                    val result = repo.findVersionIdByTag("java", "latest", Distribution.TEMURIN.some(), Platform.LINUX_X64)

                    // then: None returned
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe None
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
                    val result =
                        repo.deleteTag(
                            UniqueTag(
                                candidate = "java",
                                tag = "latest",
                                distribution = Distribution.TEMURIN.some(),
                                platform = Platform.LINUX_X64,
                            ),
                        )

                    // then: only that tag is removed
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe 1
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("27")
                }
            }

            should("return 0 when deleting a non-existent tag") {
                withCleanDatabase {
                    // when: deleting a tag that does not exist
                    val result =
                        repo.deleteTag(
                            UniqueTag(
                                candidate = "java",
                                tag = "nonexistent",
                                distribution = Distribution.TEMURIN.some(),
                                platform = Platform.LINUX_X64,
                            ),
                        )

                    // then: 0 rows affected
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe 0
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
                    val result = repo.hasTagsForVersion(versionId)
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe true
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
                    val result = repo.hasTagsForVersion(versionId)
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldBe false
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
                    val result = repo.findTagNamesByVersionId(versionId)

                    // then: correct names returned
                    result.isRight() shouldBe true
                    result.getOrNull()!! shouldContainExactlyInAnyOrder listOf("latest", "27", "27.0")
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
                                distribution = None,
                            ),
                        )

                    // when: tags are assigned with None distribution
                    repo.replaceTags(versionId, "gradle", None, Platform.UNIVERSAL, listOf("latest", "8"))

                    // then: tags are stored and retrievable
                    selectTagNames(versionId) shouldContainExactlyInAnyOrder listOf("latest", "8")

                    val resolved = repo.findVersionIdByTag("gradle", "latest", None, Platform.UNIVERSAL)
                    resolved.isRight() shouldBe true
                    resolved.getOrNull()!! shouldBe versionId.some()
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
                                distribution = None,
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
                                distribution = None,
                            ),
                        )
                    repo.replaceTags(versionId1, "gradle", None, Platform.UNIVERSAL, listOf("latest"))

                    // when: reassigning the tag to a new version
                    repo.replaceTags(versionId2, "gradle", None, Platform.UNIVERSAL, listOf("latest"))

                    // then: tag moved to the new version
                    selectTagNames(versionId1).shouldBeEmpty()
                    selectTagNames(versionId2) shouldContainExactlyInAnyOrder listOf("latest")
                }
            }
        }
    })
