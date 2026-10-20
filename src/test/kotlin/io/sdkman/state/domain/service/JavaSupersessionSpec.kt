package io.sdkman.state.domain.service

import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
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
import io.sdkman.state.support.shouldBeLeft
import io.sdkman.state.support.shouldBeRight
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

        fun Version.taggedWith(vararg tags: String): Version = copy(tags = tags.toList().some())

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

        context("the decision on a vendor post") {

            fun decisionOn(
                version: String,
                rows: List<Version>,
            ): VendorDecision = JavaSupersession.decide(posted(version), rows).shouldBeRight()

            fun hiddenBy(
                version: String,
                rows: List<Version>,
            ): List<String> = decisionOn(version, rows).hidden.map(Version::version)

            should("store a post that beats the head visible") {
                // given: a visible head
                val rows = listOf(visibleRow("25.0.4.1+1"))

                // when: a vendor posts a newer build
                val decision = decisionOn("25.0.5.0+3", rows)

                // then: the post becomes the visible head
                decision.visible shouldBe true
            }

            should("hide the head a post beats") {
                // given: a visible head
                val rows = listOf(visibleRow("25.0.4.1+1"))

                // when: a vendor posts a newer build
                val hidden = hiddenBy("25.0.5.0+3", rows)

                // then: the old head gives way
                hidden shouldContainExactlyInAnyOrder listOf("25.0.4.1+1")
            }

            should("store a post into a series without a visible member visible") {
                // given: a higher member that is hidden
                val rows = listOf(hiddenRow("25.0.4.1+1"))

                // when: a vendor posts a lower build of that series
                val decision = decisionOn("25.0.3.0", rows)

                // then: the empty series takes the post as its head
                decision.visible shouldBe true
            }

            should("hide nothing when the head is posted again") {
                // given: the only visible member of a series
                val rows = listOf(visibleRow("25.0.4.1+1"), hiddenRow("25.0.4.0+9"))

                // when: a vendor posts it again
                val hidden = hiddenBy("25.0.4.1+1", rows)

                // then: re-posting the head is idempotent
                hidden.shouldBeEmpty()
            }

            should("collapse leftover visible members onto the new head") {
                // given: three visible members of one series
                val rows = listOf(visibleRow("8.0.504"), visibleRow("8.0.472"), visibleRow("8.0.232"))

                // when: a vendor posts a build that beats them all
                val hidden = hiddenBy("8.0.512.0+1", rows)

                // then: every other visible member is hidden
                hidden shouldContainExactlyInAnyOrder listOf("8.0.504", "8.0.472", "8.0.232")
            }

            should("hide the stored spelling of the posted build") {
                // given: the legacy spelling of a build is the head
                val rows = listOf(visibleRow("27.0.0+35"))

                // when: a vendor posts its semverish spelling
                val hidden = hiddenBy("27.0.0.0+35", rows)

                // then: equal readings spelled differently give way to the post
                hidden shouldContainExactlyInAnyOrder listOf("27.0.0+35")
            }

            should("store a post below the head hidden") {
                // given: a visible head
                val rows = listOf(visibleRow("25.0.4.1+1"))

                // when: a vendor posts an older build
                val decision = decisionOn("25.0.4.0+9", rows)

                // then: the post is stored hidden
                decision.visible shouldBe false
            }

            should("hide nothing for a post below the head") {
                // given: a visible head and a lower visible leftover
                val rows = listOf(visibleRow("25.0.4.1+1"), visibleRow("25.0.1.0"))

                // when: a vendor posts a build below the head
                val hidden = hiddenBy("25.0.4.0+9", rows)

                // then: nothing else changes
                hidden.shouldBeEmpty()
            }

            should("refuse a post that cannot be ordered against the head, naming the head") {
                // given: a head with build metadata of an unknown kind
                val rows = listOf(visibleRow("21.0.9.0+r17"))

                // when: a vendor posts a numeric build of the same core
                val decision = JavaSupersession.decide(posted("21.0.9.0+1"), rows)

                // then: the post is refused and the refusal names the head
                decision shouldBeLeft Refusal("21.0.9.0+r17")
            }

            should("make a post that beats every head the only visible member") {
                // given: two visible heads that cannot be ordered
                val rows = listOf(visibleRow("22.1.0.1+r17"), visibleRow("22.1.0.1+1"))

                // when: a vendor posts a build above both
                val hidden = hiddenBy("22.1.0.2+1", rows)

                // then: both heads give way
                hidden shouldContainExactlyInAnyOrder listOf("22.1.0.1+r17", "22.1.0.1+1")
            }

            should("refuse a post that cannot be ordered against one of two heads") {
                // given: two visible heads that cannot be ordered
                val rows = listOf(visibleRow("22.1.0.1+r17"), visibleRow("22.1.0.1+1"))

                // when: a vendor posts a build that beats one head only
                val decision = JavaSupersession.decide(posted("22.1.0.1+2"), rows)

                // then: the refusal names the head it cannot be ordered against
                decision shouldBeLeft Refusal("22.1.0.1+r17")
            }

            should("make a post that is one of the heads the only visible member") {
                // given: two visible heads that cannot be ordered
                val rows = listOf(visibleRow("22.1.0.1+r17"), visibleRow("22.1.0.1+1"))

                // when: a vendor posts one of them again
                val hidden = hiddenBy("22.1.0.1+1", rows)

                // then: the other head gives way
                hidden shouldContainExactlyInAnyOrder listOf("22.1.0.1+r17")
            }

            should("store a hidden row that beats the head visible again") {
                // given: a hidden row above the visible head
                val rows = listOf(hiddenRow("25.0.4.1+1"), visibleRow("25.0.4.0+9"))

                // when: a vendor posts the hidden row again
                val decision = decisionOn("25.0.4.1+1", rows)

                // then: it comes back as the head
                decision.visible shouldBe true
            }

            should("leave unreadable rows visible") {
                // given: an unreadable row beside a readable member
                val rows = listOf(visibleRow("25.0.4.r25"), visibleRow("25.0.1.0+1"))

                // when: a vendor posts a newer build
                val hidden = hiddenBy("25.0.5.0+1", rows)

                // then: only the readable member gives way
                hidden shouldContainExactlyInAnyOrder listOf("25.0.1.0+1")
            }

            should("hide no member of another variant series") {
                // given: visible crac, fx and plain builds of one release
                val rows = listOf(visibleRow("17.0.20-crac+1.2"), visibleRow("17.0.20-fx+1.1"), visibleRow("17.0.20+1.1"))

                // when: a vendor posts a newer crac build
                val hidden = hiddenBy("17.0.21.0-crac+1", rows)

                // then: only the crac series gives way
                hidden shouldContainExactlyInAnyOrder listOf("17.0.20-crac+1.2")
            }
        }

        context("the tags a vendor post moves") {

            fun tagsMovedBy(
                version: String,
                rows: List<Version>,
            ): List<String> = JavaSupersession.decide(posted(version), rows).shouldBeRight().tagsToMove

            should("move the feature tag to a head that beats its holder") {
                // given: the head holds the feature tag
                val rows = listOf(visibleRow("25.0.4.1+1").taggedWith("lts", "25"))

                // when: a vendor posts a newer build
                val tags = tagsMovedBy("25.0.5.0+3", rows)

                // then: the feature tag moves to the new head
                tags shouldContain "25"
            }

            should("move the feature tag when no row holds it") {
                // given: an untagged head
                val rows = listOf(visibleRow("21.0.12+1.1"))

                // when: a vendor posts a newer build
                val tags = tagsMovedBy("21.0.13.0+1", rows)

                // then: the feature tag reaches the new head
                tags shouldContain "21"
            }

            should("move the feature tag off an unreadable holder") {
                // given: an unreadable row holds the feature tag
                val rows = listOf(visibleRow("25.0.4.r25").taggedWith("25"))

                // when: a vendor posts a build of that feature
                val tags = tagsMovedBy("25.0.5.0+1", rows)

                // then: the feature tag moves to the new head
                tags shouldContain "25"
            }

            should("move the feature tag off another spelling of the posted build") {
                // given: the legacy spelling of a build holds the feature tag
                val rows = listOf(visibleRow("27.0.0+35").taggedWith("27"))

                // when: a vendor posts its semverish spelling
                val tags = tagsMovedBy("27.0.0.0+35", rows)

                // then: the post beats the stored spelling and takes the tag
                tags shouldContain "27"
            }

            should("move the feature tag from the feature line to a higher interim head") {
                // given: the feature line head holds lts and the feature tag
                val rows = listOf(visibleRow("21.0.0.0.1").taggedWith("lts", "21"))

                // when: a vendor posts the first interim release
                val tags = tagsMovedBy("21.5.0.0.1", rows)

                // then: the feature tag goes to the highest head of the feature
                tags shouldContain "21"
            }

            should("leave the feature tag on a higher holder of another series") {
                // given: the interim head holds the feature tag
                val rows = listOf(visibleRow("21.5.0.0.1").taggedWith("21"))

                // when: a vendor posts a feature line build
                val tags = tagsMovedBy("21.0.13.0+1", rows)

                // then: the lower head does not take the feature tag
                tags shouldNotContain "21"
            }

            should("move lts from a hidden row of the series") {
                // given: lts left on a hidden row of the series
                val rows = listOf(hiddenRow("25.0.4.1+1").taggedWith("lts"), visibleRow("25.0.3.0").taggedWith("25"))

                // when: a vendor posts the next head of that series
                val tags = tagsMovedBy("25.0.5.0+1", rows)

                // then: lts heals onto the new head
                tags shouldContain "lts"
            }

            should("keep lts out of a post into another series") {
                // given: lts on the head of the feature 25 series
                val rows = listOf(visibleRow("25.0.4.1+1").taggedWith("lts", "25"))

                // when: a vendor posts into a new feature series
                val tags = tagsMovedBy("29.0.0.0+ea.1", rows)

                // then: lts stays in its series
                tags shouldNotContain "lts"
            }

            should("keep lts on the feature line when an interim head arrives") {
                // given: lts on the feature line head
                val rows = listOf(visibleRow("21.0.0.0.1").taggedWith("lts", "21"))

                // when: a vendor posts the first interim release
                val tags = tagsMovedBy("21.5.0.0.1", rows)

                // then: the interim line is another series, so lts stays
                tags shouldNotContain "lts"
            }

            should("move no lts when no row holds it") {
                // given: an untagged head
                val rows = listOf(visibleRow("21.0.12+1.1"))

                // when: a vendor posts a newer build
                val tags = tagsMovedBy("21.0.13.0+1", rows)

                // then: a vendor post never creates lts
                tags shouldNotContain "lts"
            }

            should("move latest to a higher series, early access included") {
                // given: the GA head of feature 27 holds latest
                val rows = listOf(visibleRow("27.0.0.0+35").taggedWith("27", "latest"))

                // when: a vendor posts an early-access build of feature 28
                val tags = tagsMovedBy("28.0.0.0+ea.5", rows)

                // then: latest tracks the highest plain head
                tags shouldContain "latest"
            }

            should("move latest when no row holds it") {
                // given: an untagged head
                val rows = listOf(visibleRow("25.0.4.1+1"))

                // when: a vendor posts a newer build
                val tags = tagsMovedBy("25.0.5.0+1", rows)

                // then: latest reaches the new head
                tags shouldContain "latest"
            }

            should("move latest off an unreadable holder") {
                // given: an unreadable row holds latest
                val rows = listOf(visibleRow("25.0.4.r25").taggedWith("latest"))

                // when: a vendor posts a readable build
                val tags = tagsMovedBy("25.0.5.0+1", rows)

                // then: latest moves to the new head
                tags shouldContain "latest"
            }

            should("leave latest on a higher holder") {
                // given: an early-access head of feature 28 holds latest
                val rows =
                    listOf(
                        visibleRow("28.0.0.0+ea.20").taggedWith("28", "latest"),
                        visibleRow("27.0.0+35").taggedWith("27"),
                    )

                // when: a vendor posts a newer feature 27 build
                val tags = tagsMovedBy("27.0.1.0+3", rows)

                // then: latest stays on the higher series
                tags shouldNotContain "latest"
            }

            should("leave latest on a holder the head cannot be ordered against") {
                // given: a hidden row with unknown build metadata holds latest
                val rows = listOf(hiddenRow("22.1.0.1+r17").taggedWith("latest"), visibleRow("22.1.0.0"))

                // when: a vendor posts a numeric build of the same core
                val tags = tagsMovedBy("22.1.0.1+1", rows)

                // then: latest stays where it is
                tags shouldNotContain "latest"
            }

            should("move no tag from a post below the head") {
                // given: a visible head and no tag holder
                val rows = listOf(visibleRow("25.0.4.1+1"))

                // when: a vendor posts an older build
                val tags = tagsMovedBy("25.0.4.0+9", rows)

                // then: a post that is not the head gains no tag
                tags.shouldBeEmpty()
            }

            should("move no tag from the head of an fx series") {
                // given: visible fx and plain builds and no tag holder
                val rows = listOf(visibleRow("21.0.12-fx+1.1"), visibleRow("21.0.12+1.1"))

                // when: a vendor posts a newer fx build
                val tags = tagsMovedBy("21.0.13.0-fx+1", rows)

                // then: a variant series never gains a tag from a vendor post
                tags.shouldBeEmpty()
            }

            should("move no tag the posted head already holds") {
                // given: the head holds lts, its feature tag and latest
                val rows = listOf(visibleRow("25.0.4.1+1").taggedWith("lts", "25", "latest"))

                // when: a vendor posts the head again
                val tags = tagsMovedBy("25.0.4.1+1", rows)

                // then: every tag is already in place
                tags.shouldBeEmpty()
            }
        }
    })
