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

        fun latestMovesFor(
            rows: List<Version>,
            ltsFeature: Int = 25,
        ): List<TagMove> = JavaTagPlacement.movesFor(rows, ltsFeature).filter { move -> move.tag == "latest" }

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

        context("the lts a platform without the LTS feature keeps") {

            should("keep lts on its holder until the platform has the LTS feature") {
                // given: lts on 25 on two platforms, and a GA of feature 29 on one of them
                val rows =
                    listOf(
                        row("29.0.0.0+35", Platform.LINUX_X64),
                        row("25.0.8.0+7", Platform.LINUX_X64, tags = listOf("lts")),
                        row("25.0.8.0+7", Platform.MAC_X64, tags = listOf("lts")),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: only the platform with the LTS feature hands lts over
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "lts", "29.0.0.0+35"))
            }

            should("move a kept lts to the new head of its series") {
                // given: lts on a hidden build of 25, a newer head of 25 and a head of 27
                val rows =
                    listOf(
                        row("25.0.8.0+7", Platform.MAC_X64, visible = false, tags = listOf("lts")),
                        row("25.0.9.0+5", Platform.MAC_X64),
                        row("27.0.1.0+9", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: lts follows its own series, not the highest feature
                moves shouldContainExactly listOf(TagMove.Place(Platform.MAC_X64, "lts", "25.0.9.0+5"))
            }

            should("place no lts on a platform that holds none") {
                // given: heads of 25 and 27 without lts
                val rows = listOf(row("25.0.8.0+7", Platform.MAC_X64), row("27.0.1.0+9", Platform.MAC_X64))

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: lts never lands afresh on an older feature
                moves.shouldBeEmpty()
            }

            should("remove lts whose series has no visible head") {
                // given: lts on a hidden build of 25, and a visible head of 21 only
                val rows =
                    listOf(
                        row("25.0.8.0+7", Platform.MAC_X64, visible = false, tags = listOf("lts")),
                        row("21.0.16.0+3", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: lts leaves the platform
                moves shouldContainExactly listOf(TagMove.Remove(Platform.MAC_X64, "lts"))
            }

            should("remove lts whose series has only an early access head") {
                // given: lts on a hidden build of 25, superseded by early access of the same series
                val rows =
                    listOf(
                        row("25.0.8.0+7", Platform.MAC_X64, visible = false, tags = listOf("lts")),
                        row("25.0.9.0+ea.2", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: early access does not keep lts
                moves shouldContainExactly listOf(TagMove.Remove(Platform.MAC_X64, "lts"))
            }

            should("remove lts from an unreadable holder") {
                // given: lts on an unreadable row beside a head of 25
                val rows =
                    listOf(
                        row("25.0.4.r25", Platform.MAC_X64, tags = listOf("lts")),
                        row("25.0.8.0+7", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: an unreadable holder belongs to no series to keep lts in
                moves shouldContainExactly listOf(TagMove.Remove(Platform.MAC_X64, "lts"))
            }

            should("remove lts from an fx holder") {
                // given: lts on the head of an fx series beside the plain head of 25
                val rows =
                    listOf(
                        row("25.0.8.0-fx+7", Platform.MAC_X64, tags = listOf("lts")),
                        row("25.0.8.0+7", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: lts never stays on a variant, nor hops to the plain series
                moves shouldContainExactly listOf(TagMove.Remove(Platform.MAC_X64, "lts"))
            }

            should("leave a kept lts in place when its series heads cannot be ordered") {
                // given: lts on a hidden build of 25, and two visible builds of one newer core that cannot be ordered
                val rows =
                    listOf(
                        row("25.0.4.0+1", Platform.MAC_X64, visible = false, tags = listOf("lts")),
                        row("25.0.5.0+1", Platform.MAC_X64),
                        row("25.0.5.0+r17", Platform.MAC_X64),
                    )

                // when: placing lts at LTS feature 29
                val moves = ltsMovesFor(rows, ltsFeature = 29)

                // then: no row is singled out, so lts is neither moved nor removed
                moves.shouldBeEmpty()
            }
        }

        context("the latest a platform takes") {

            should("place latest on the head of the top feature") {
                // given: GA heads of features 21, 25 and 27
                val rows = listOf(row("21.0.13.0+11"), row("25.0.5.0+8"), row("27.0.1.0+9"))

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: the highest feature takes latest
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "latest", "27.0.1.0+9"))
            }

            should("remove latest from a platform without the top feature") {
                // given: latest on 27 on one platform, and on 17 on a platform that never ships 27
                val rows =
                    listOf(
                        row("27.0.1.0+9", Platform.LINUX_X64, tags = listOf("latest")),
                        row("17.0.21.0+9", Platform.LINUX_X32, tags = listOf("latest")),
                    )

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: latest never falls back to an older feature
                moves shouldContainExactly listOf(TagMove.Remove(Platform.LINUX_X32, "latest"))
            }

            should("let early access set the top feature") {
                // given: a GA head of 27 on two platforms, and early access of 28 on one of them
                val rows =
                    listOf(
                        row("27.0.1.0+3", Platform.LINUX_X64, tags = listOf("latest")),
                        row("28.0.0.0+ea.24", Platform.LINUX_X64),
                        row("27.0.1.0+3", Platform.MAC_ARM64, tags = listOf("latest")),
                    )

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: early access takes latest, and the platform without it loses latest
                moves shouldContainExactlyInAnyOrder
                    listOf(
                        TagMove.Place(Platform.LINUX_X64, "latest", "28.0.0.0+ea.24"),
                        TagMove.Remove(Platform.MAC_ARM64, "latest"),
                    )
            }

            should("keep a newer latest above an lts held on the old LTS feature") {
                // given: lts kept on 25 and a head of 28, with no 29
                val rows =
                    listOf(
                        row("25.0.8.0+1", tags = listOf("lts")),
                        row("28.0.1.0+1", tags = listOf("latest")),
                        row("28.0.2.0+1"),
                    )

                // when: placing latest at LTS feature 29
                val moves = latestMovesFor(rows, ltsFeature = 29)

                // then: the lts floor is 25, so latest moves to the new head of 28
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "latest", "28.0.2.0+1"))
            }

            should("remove latest below the LTS feature on a platform without lts") {
                // given: 21 is the highest feature on every platform, and latest is held on two of them
                val rows =
                    listOf(
                        row("21.0.9.0+1", Platform.LINUX_X64, visible = false, tags = listOf("latest")),
                        row("21.0.10.0+1", Platform.LINUX_X64),
                        row("21.0.9.0+1", Platform.MAC_X64, tags = listOf("latest")),
                    )

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: latest never sits below the LTS feature
                moves shouldContainExactlyInAnyOrder
                    listOf(
                        TagMove.Remove(Platform.LINUX_X64, "latest"),
                        TagMove.Remove(Platform.MAC_X64, "latest"),
                    )
            }

            should("take the lts floor from the holder left after lts placement") {
                // given: lts on a hidden build of 25 with no visible head of 25, and latest on a head of 28
                val rows =
                    listOf(
                        row("25.0.8.0+7", visible = false, tags = listOf("lts")),
                        row("28.0.1.0+1", tags = listOf("latest")),
                    )

                // when: placing at LTS feature 29
                val moves = JavaTagPlacement.movesFor(rows, ltsFeature = 29)

                // then: removing lts raises the floor to 29, so latest goes as well
                moves shouldContainExactly
                    listOf(
                        TagMove.Remove(Platform.LINUX_X64, "lts"),
                        TagMove.Remove(Platform.LINUX_X64, "latest"),
                    )
            }

            should("take the lts floor from the head lts is placed on") {
                // given: lts on a hidden build of 26 above the visible head of 25
                val rows = listOf(row("25.0.5.0+8"), row("26.0.1.0+1", visible = false, tags = listOf("lts")))

                // when: placing at LTS feature 25
                val moves = JavaTagPlacement.movesFor(rows, ltsFeature = 25)

                // then: lts moves down to 25, so the floor is 25 and latest joins it
                moves shouldContainExactly
                    listOf(
                        TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+8"),
                        TagMove.Place(Platform.LINUX_X64, "latest", "25.0.5.0+8"),
                    )
            }

            should("place lts and latest on one head when nothing is newer than the LTS feature") {
                // given: GA heads of 21 and 25 only
                val rows = listOf(row("21.0.13.0+10"), row("25.0.5.0+9"))

                // when: placing at LTS feature 25
                val moves = JavaTagPlacement.movesFor(rows, ltsFeature = 25)

                // then: the head of 25 carries both tags
                moves shouldContainExactly
                    listOf(
                        TagMove.Place(Platform.LINUX_X64, "lts", "25.0.5.0+9"),
                        TagMove.Place(Platform.LINUX_X64, "latest", "25.0.5.0+9"),
                    )
            }

            should("place latest on an interim head above the feature line") {
                // given: the feature line head and a higher interim head of the top feature
                val rows = listOf(row("27.0.1.0+9"), row("27.2.0.0+3"))

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: the interim head outranks the feature line
                moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "latest", "27.2.0.0+3"))
            }

            listOf("27.0.2.0-fx+1", "28.0.1.0-fx+1").forEach { variant ->
                should("move latest off the fx head $variant onto the plain head") {
                    // given: latest on an fx head above the plain head of 27
                    val rows = listOf(row("27.0.1.0+9"), row(variant, tags = listOf("latest")))

                    // when: placing latest at LTS feature 25
                    val moves = latestMovesFor(rows)

                    // then: an fx head neither carries latest nor sets the top feature
                    moves shouldContainExactly listOf(TagMove.Place(Platform.LINUX_X64, "latest", "27.0.1.0+9"))
                }
            }

            should("leave latest on the head that holds it when the highest heads cannot be ordered") {
                // given: two visible builds of one core of 27 that cannot be ordered, the second holding latest
                val rows = listOf(row("27.0.1.0+1"), row("27.0.1.0+r17", tags = listOf("latest")))

                // when: placing latest at LTS feature 25
                val moves = latestMovesFor(rows)

                // then: latest stays where it is
                moves.shouldBeEmpty()
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
