package io.sdkman.state.domain.service

import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.sdkman.state.domain.model.Distribution
import io.sdkman.state.domain.model.JAVA_CANDIDATE
import io.sdkman.state.domain.model.JavaSeries
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.ReleaseLine
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.model.series
import io.sdkman.state.support.shouldBeSome
import java.math.BigInteger

class JavaSupersessionSpec :
    ShouldSpec({

        fun posted(version: String): JavaVersion = JavaVersion.parse(version).shouldBeSome()

        fun stored(version: String): JavaVersion = JavaVersion.read(version).shouldBeSome()

        fun row(
            version: String,
            visible: Boolean,
        ): Version =
            Version(
                candidate = JAVA_CANDIDATE,
                version = version,
                platform = Platform.LINUX_X64,
                url = "https://example.com/jdk-$version.tar.gz",
                visible = visible.some(),
                distribution = Distribution.TEMURIN.some(),
            )

        fun visibleRow(version: String): Version = row(version, visible = true)

        fun hiddenRow(version: String): Version = row(version, visible = false)

        fun membersOf(
            version: String,
            rows: List<Version>,
        ): List<SeriesMember> = JavaSupersession.members(posted(version).series, rows)

        fun List<SeriesMember>.versions(): List<String> = map { member -> member.row.version }

        context("the series of a version") {

            should("derive feature 25, the feature line and no variant from 25.0.4.0") {
                // given: a feature line version
                val version = posted("25.0.4.0")

                // when: deriving its series
                val series = version.series

                // then: the feature, the feature line and no variant make the series
                series shouldBe JavaSeries(BigInteger.valueOf(25), ReleaseLine.FEATURE, none())
            }

            should("place a version with a non-zero interim on the interim line") {
                // given: an interim release
                val version = posted("25.3.4.0")

                // when: deriving its series
                val series = version.series

                // then: it is on the interim line of its feature
                series.releaseLine shouldBe ReleaseLine.INTERIM
            }

            should("separate the feature line from the interim line of one feature") {
                // given: a feature line version
                val featureLine = posted("25.0.4.0").series

                // when: deriving the series of an interim release of the same feature
                val interimLine = posted("25.3.4.0").series

                // then: the release line keeps them apart
                interimLine shouldNotBe featureLine
            }

            should("place successive interim releases of one feature in one series") {
                // given: an interim release
                val earlierInterim = posted("25.3.4.0").series

                // when: deriving the series of the next interim release
                val laterInterim = posted("25.4.1.0").series

                // then: each new interim release replaces the previous one
                laterInterim shouldBe earlierInterim
            }

            should("separate features") {
                // given: a feature 21 version
                val feature21 = posted("21.0.1.0").series

                // when: deriving the series of a feature 25 version
                val feature25 = posted("25.0.1.0").series

                // then: the feature keeps them apart
                feature25 shouldNotBe feature21
            }

            should("separate a variant series from the plain series") {
                // given: a plain version
                val plain = posted("25.0.1.0").series

                // when: deriving the series of the fx build of the same release
                val fx = posted("25.0.1.0-fx").series

                // then: the variant keeps them apart
                fx shouldNotBe plain
            }

            should("place a legacy dot variant in the series of its semverish spelling") {
                // given: the semverish spelling of an fx build
                val semverish = posted("26.0.2.0-fx").series

                // when: deriving the series of its legacy stored spelling
                val legacy = stored("26.0.2.fx").series

                // then: both spell the same variant, so they share a series
                legacy shouldBe semverish
            }

            listOf(
                "25.0.1.0" to true,
                "25.0.1.0-fx" to false,
                "25.0.1.0-crac" to false,
            ).forEach { (version, expectedPlain) ->
                should("find the series of $version plain: $expectedPlain") {
                    // given: a version with or without a variant
                    val series = posted(version).series

                    // when: asking whether its series is plain
                    val plain = series.isPlain

                    // then: only the series without a variant is plain
                    plain shouldBe expectedPlain
                }
            }
        }

        context("the members of a series") {

            should("include hidden members as well as visible ones") {
                // given: a visible and a hidden row of one series
                val rows = listOf(visibleRow("25.0.1.0+1"), hiddenRow("25.0.0.0"))

                // when: collecting the members of that series
                val members = membersOf("25.0.5.0", rows)

                // then: visibility takes no part in membership
                members.versions() shouldContainExactlyInAnyOrder listOf("25.0.1.0+1", "25.0.0.0")
            }

            should("read a legacy dot variant row into its variant series") {
                // given: a plain three-component row and a legacy dot variant row
                val rows = listOf(visibleRow("17.0.20"), visibleRow("17.0.19.crac"))

                // when: collecting the members of the crac series of feature 17
                val members = membersOf("17.0.20.0-crac+1", rows)

                // then: only the legacy crac row reads into it
                members.versions() shouldContainExactlyInAnyOrder listOf("17.0.19.crac")
            }

            should("exclude rows of other series") {
                // given: rows of another release line, another feature and another variant
                val rows = listOf(visibleRow("25.3.4.0"), visibleRow("21.0.1.0"), visibleRow("25.0.1.0-fx"))

                // when: collecting the members of the plain feature line of feature 25
                val members = membersOf("25.0.5.0", rows)

                // then: none of them belongs to it
                members.shouldBeEmpty()
            }

            should("exclude unreadable rows") {
                // given: rows outside the grammar even after normalisation
                val rows = listOf(visibleRow("25.0.4.r25"), visibleRow("25.r25"))

                // when: collecting the members of the plain feature line of feature 25
                val members = membersOf("25.0.5.0", rows)

                // then: unreadable rows belong to no series
                members.shouldBeEmpty()
            }

            should("carry the reading of each member") {
                // given: a three-component row
                val rows = listOf(visibleRow("17.0.20"))

                // when: collecting the members of its series
                val members = membersOf("17.0.21.0", rows)

                // then: each member pairs the row with its reading
                members.map(SeriesMember::reading) shouldBe listOf(stored("17.0.20"))
            }
        }

        context("the heads of a series") {

            fun headsOf(
                seriesVersion: String,
                rows: List<Version>,
            ): List<String> = JavaSupersession.heads(membersOf(seriesVersion, rows)).versions()

            should("pick the highest visible member") {
                // given: three visible members
                val rows = listOf(visibleRow("25.0.1.0+1"), visibleRow("25.0.4.1+1"), visibleRow("25.0.3.0"))

                // when: finding the heads
                val heads = headsOf("25.0.0.0", rows)

                // then: the highest member is the only head
                heads shouldContainExactlyInAnyOrder listOf("25.0.4.1+1")
            }

            should("ignore a higher hidden member") {
                // given: a hidden member above a visible one
                val rows = listOf(hiddenRow("25.0.5.0"), visibleRow("25.0.4.0"))

                // when: finding the heads
                val heads = headsOf("25.0.0.0", rows)

                // then: the head is the highest visible member
                heads shouldContainExactlyInAnyOrder listOf("25.0.4.0")
            }

            should("count a row without stored visibility as visible") {
                // given: a member whose visibility is absent
                val rows = listOf(visibleRow("25.0.4.0").copy(visible = none()))

                // when: finding the heads
                val heads = headsOf("25.0.0.0", rows)

                // then: an absent visibility reads as visible
                heads shouldContainExactlyInAnyOrder listOf("25.0.4.0")
            }

            should("find no head when no member is visible") {
                // given: hidden members only
                val rows = listOf(hiddenRow("25.0.4.0"), hiddenRow("25.0.3.0"))

                // when: finding the heads
                val heads = headsOf("25.0.0.0", rows)

                // then: the series has no head
                heads.shouldBeEmpty()
            }

            should("count two visible members that cannot be ordered as two heads") {
                // given: visible members of equal core, one with unknown build metadata
                val rows = listOf(visibleRow("22.1.0.1+r17"), visibleRow("22.1.0.1+1"))

                // when: finding the heads of their interim series
                val heads = headsOf("22.1.0.0", rows)

                // then: neither outranks the other, so each is a head
                heads shouldContainExactlyInAnyOrder listOf("22.1.0.1+r17", "22.1.0.1+1")
            }

            should("pick a member that outranks two unordered members as the only head") {
                // given: two unordered visible members and one above both
                val rows = listOf(visibleRow("22.1.0.1+r17"), visibleRow("22.1.0.1+1"), visibleRow("22.1.0.2+1"))

                // when: finding the heads of their interim series
                val heads = headsOf("22.1.0.0", rows)

                // then: the member that outranks both is the head
                heads shouldContainExactlyInAnyOrder listOf("22.1.0.2+1")
            }

            should("count two visible spellings of one build as two heads") {
                // given: the legacy and semverish spellings of one build, both visible
                val rows = listOf(visibleRow("25.0.0+35"), visibleRow("25.0.0.0+35"))

                // when: finding the heads
                val heads = headsOf("25.0.0.0", rows)

                // then: they read the same, so neither outranks the other
                heads shouldContainExactlyInAnyOrder listOf("25.0.0+35", "25.0.0.0+35")
            }
        }

        context("the rows an admin post hides") {

            should("hide every other visible member of the series whatever its order") {
                // given: a visible member above and one below the posted version
                val rows = listOf(visibleRow("25.0.4.1+1"), visibleRow("25.0.1.0"))

                // when: the admin posts a version of that series
                val hidden = JavaSupersession.overrideHides(posted("25.0.4.0+9"), rows)

                // then: both give way to the post
                hidden.map(Version::version) shouldContainExactlyInAnyOrder listOf("25.0.4.1+1", "25.0.1.0")
            }

            should("never hide the posted row itself") {
                // given: the posted version is already stored visible
                val rows = listOf(visibleRow("25.0.4.0+9"))

                // when: the admin posts it again
                val hidden = JavaSupersession.overrideHides(posted("25.0.4.0+9"), rows)

                // then: it stays out of the hidden rows
                hidden.shouldBeEmpty()
            }

            should("hide another spelling of the posted build") {
                // given: the legacy spelling of a build is visible
                val rows = listOf(visibleRow("27.0.0+35"))

                // when: the admin posts its semverish spelling
                val hidden = JavaSupersession.overrideHides(posted("27.0.0.0+35"), rows)

                // then: the stored spelling gives way to the post
                hidden.map(Version::version) shouldContainExactlyInAnyOrder listOf("27.0.0+35")
            }

            should("hide nothing but visible members of the posted series") {
                // given: a hidden member, a row of another series and an unreadable row
                val rows = listOf(hiddenRow("25.0.3.0"), visibleRow("25.3.0.0"), visibleRow("25.0.4.r25"))

                // when: the admin posts a version of the plain feature line of feature 25
                val hidden = JavaSupersession.overrideHides(posted("25.0.4.0+9"), rows)

                // then: none of them is hidden
                hidden.shouldBeEmpty()
            }
        }
    })
