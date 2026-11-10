package io.sdkman.state.domain.service

import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.support.javaVersion

class JavaTagPlacementSpec :
    ShouldSpec({

        fun row(
            version: String,
            platform: Platform = Platform.LINUX_X64,
            visible: Boolean = true,
            tags: List<String> = emptyList(),
        ): Version = javaVersion(Distribution.TEMURIN, version, platform, visible).copy(tags = tags.some())

        fun ltsMovesFor(
            rows: List<Version>,
            ltsFeature: Int = 25,
        ): List<TagMove> = JavaTagPlacement.movesFor(rows, ltsFeature).filter { move -> move.tag == "lts" }

        context("the lts a platform takes") {

            should("place lts on the head of the LTS feature only") {
                // given: GA heads of features 21, 25 and 27
                val rows = listOf(row("21.0.13.0+11"), row("25.0.5.0+8"), row("27.0.1.0+9"))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: neither an older nor a newer feature takes lts
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+8"))
            }

            should("place lts on an interim head above the feature line") {
                // given: the feature line head and a higher interim head of feature 25
                val rows = listOf(row("25.0.5.0+3"), row("25.4.4.1+1", tags = listOf("25", "latest")))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: the interim head outranks the feature line
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "lts", "25.4.4.1+1"))
            }

            should("never place lts on early access of the LTS feature") {
                // given: only early access of feature 29
                val rows = listOf(row("29.0.0.0+ea.31"))

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: early access is not the stable choice
                moves.shouldBeEmpty()
            }

            should("move lts off a hidden holder onto the head") {
                // given: lts left on a hidden row below the visible head
                val rows = listOf(row("25.0.4.1+1", visible = false, tags = listOf("lts")), row("25.0.5.0+8"))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: the head takes lts, which leaves the hidden row
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+8"))
            }

            should("move nothing when the head already holds lts") {
                // given: the head of feature 25 holds lts
                val rows = listOf(row("25.0.5.0+8", tags = listOf("lts", "25")))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: lts is already in place
                moves.shouldBeEmpty()
            }

            should("place lts on the head of each platform") {
                // given: a different head of feature 25 on each of two platforms
                val rows = listOf(row("25.0.5.0+8", Platform.LINUX_X64), row("25.0.4.0+7", Platform.MAC_X64))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: each platform takes its own head
                moves shouldContainExactlyInAnyOrder
                    listOf(
                        TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+8"),
                        TagMove.Place(Platform.MAC_X64, "lts", "25.0.4.0+7"),
                    )
            }

            listOf("25.0.6.0-fx+1", "25.0.6.0-crac+1").forEach { variant ->
                should("never place lts on the higher variant head $variant") {
                    // given: a plain head and a higher variant head of feature 25
                    val rows = listOf(row("25.0.5.0+8"), row(variant))

                    // when: placing lts at LTS feature 25
                    val moves = ltsMovesFor(rows)

                    // then: only the plain head takes lts
                    moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+8"))
                }
            }
        }

        context("lts when the highest heads cannot be ordered") {

            should("leave lts on the head that holds it") {
                // given: two visible builds of one core that cannot be ordered, the second holding lts
                val rows = listOf(row("25.0.5.0+1"), row("25.0.5.0+r17", tags = listOf("lts", "latest")))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: lts stays where it is
                moves.shouldBeEmpty()
            }

            should("place no lts when neither head holds it") {
                // given: two visible builds of one core that cannot be ordered, neither tagged
                val rows = listOf(row("25.0.5.0+1"), row("25.0.5.0+r17"))

                // when: placing lts at LTS feature 25
                val moves = ltsMovesFor(rows)

                // then: no row is singled out
                moves.shouldBeEmpty()
            }
        }
    })
