package io.sdkman.state.domain.model

import arrow.core.none
import arrow.core.some
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.sdkman.state.domain.model.BuildMetadata.Absent
import io.sdkman.state.domain.model.BuildMetadata.EarlyAccess
import io.sdkman.state.domain.model.BuildMetadata.Numeric
import io.sdkman.state.domain.model.BuildMetadata.Unknown
import io.sdkman.state.support.posted
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.stored
import java.math.BigInteger

class JavaVersionSpec :
    ShouldSpec({

        fun numbers(vararg components: Int): List<BigInteger> = components.map { it.toBigInteger() }

        context("parsing a posted version") {

            should("split a version into core, variant and build metadata") {
                // given: a version using every section of the grammar
                val version = "25.0.0.1.1.2-crac+ea.3"

                // when: parsing it
                val result = JavaVersion.parse(version)

                // then: each section is read into its own field
                result shouldBeSome
                    JavaVersion(
                        spelling = version,
                        core = numbers(25, 0, 0, 1, 1, 2),
                        variant = "crac".some(),
                        build = EarlyAccess(BigInteger.valueOf(3)),
                    )
            }

            should("read a version without variant or build metadata") {
                // given: a bare four-component version
                val version = "25.0.2.0"

                // when: parsing it
                val result = JavaVersion.parse(version)

                // then: it has no variant and absent build metadata
                result shouldBeSome
                    JavaVersion(
                        spelling = version,
                        core = numbers(25, 0, 2, 0),
                        variant = none(),
                        build = Absent,
                    )
            }

            listOf(
                "25.0.0.0+ea.20" to EarlyAccess(BigInteger.valueOf(20)),
                "25.0.0.0+1" to Numeric(numbers(1)),
                "25.0.0.0+1.10" to Numeric(numbers(1, 10)),
                "25.0.0.0+01" to Numeric(numbers(1)),
                "22.1.0.1+r17" to Unknown(listOf("r17")),
                "25.0.0.0+1.r25" to Unknown(listOf("1", "r25")),
                "25.0.0.0+ea" to Unknown(listOf("ea")),
                "25.0.0.0+ea.1.2" to Unknown(listOf("ea", "1", "2")),
                "25.0.0.0+ea.r1" to Unknown(listOf("ea", "r1")),
                "25.0.0.0+EA.1" to Unknown(listOf("EA", "1")),
            ).forEach { (version, expectedBuild) ->
                should("classify the build metadata of $version as $expectedBuild") {
                    // given: a version carrying build metadata

                    // when: parsing it
                    val result = JavaVersion.parse(version)

                    // then: the metadata is early access, numeric or of an unknown kind
                    result.shouldBeSome().build shouldBe expectedBuild
                }
            }

            listOf(
                "25.0.1" to "a three-component core is never padded on a post",
                "26.0.2.fx" to "a legacy dot variant is never rewritten on a post",
                "25.0.2.0.0" to "a final trailing element is never zero",
            ).forEach { (version, reason) ->
                should("reject '$version' because $reason") {
                    // given: a version outside the grammar

                    // when: parsing it
                    val result = JavaVersion.parse(version)

                    // then: it is not a java version
                    result.shouldBeNone()
                }
            }
        }

        context("reading a stored row") {

            listOf(
                "27.0.0+35" to JavaVersion("27.0.0+35", numbers(27, 0, 0, 0), none(), Numeric(numbers(35))),
                "17.0.20" to JavaVersion("17.0.20", numbers(17, 0, 20, 0), none(), Absent),
                "26.0.2.fx" to JavaVersion("26.0.2.fx", numbers(26, 0, 2, 0), "fx".some(), Absent),
                "17.0.19.crac" to JavaVersion("17.0.19.crac", numbers(17, 0, 19, 0), "crac".some(), Absent),
                "26.0.2.fx+1" to JavaVersion("26.0.2.fx+1", numbers(26, 0, 2, 0), "fx".some(), Numeric(numbers(1))),
                "21.0.12-fx+1.1" to JavaVersion("21.0.12-fx+1.1", numbers(21, 0, 12, 0), "fx".some(), Numeric(numbers(1, 1))),
                "25.3.4+1.r25" to JavaVersion("25.3.4+1.r25", numbers(25, 3, 4, 0), none(), Unknown(listOf("1", "r25"))),
                "25.0.4.1+1" to JavaVersion("25.0.4.1+1", numbers(25, 0, 4, 1), none(), Numeric(numbers(1))),
            ).forEach { (stored, expected) ->
                should("read stored $stored as core ${expected.core}, variant ${expected.variant} and ${expected.build}") {
                    // given: a stored row in either generation of spelling

                    // when: reading it for comparison
                    val result = JavaVersion.read(stored)

                    // then: the legacy variant and the three-component core are normalised
                    result shouldBeSome expected
                }
            }

            should("keep the stored spelling of a normalised row") {
                // given: a stored row whose core reads padded to four components
                val stored = "27.0.0+35"

                // when: reading it for comparison
                val result = JavaVersion.read(stored)

                // then: the stored string is never rewritten
                result.shouldBeSome().spelling shouldBe stored
            }

            listOf(
                "25.0.4.r25" to "r25 is a runtime target, not a variant",
                "25.r25" to "the core has one component",
                "16.ea.36" to "early access sits in the core",
                "26.0" to "only a three-component core is padded",
                "26.0.2.fx.crac" to "a row carries a single variant",
                "26.0.2.lts" to "lts is outside the variant vocabulary",
                "" to "the version is empty",
            ).forEach { (stored, reason) ->
                should("find stored '$stored' unreadable because $reason") {
                    // given: a stored row outside the grammar even after normalisation

                    // when: reading it for comparison
                    val result = JavaVersion.read(stored)

                    // then: it belongs to no series
                    result.shouldBeNone()
                }
            }
        }

        context("ranking versions by precedence") {

            listOf(
                listOf(
                    "25.0.0.0+ea.1",
                    "25.0.0.0+ea.2",
                    "25.0.0.0",
                    "25.0.0.0+1",
                    "25.0.0.0+1.1",
                    "25.0.0.0+1.2",
                    "25.0.0.0+1.10",
                    "25.0.0.0+2",
                    "25.0.0.1",
                    "25.0.0.1.1",
                    "25.0.1.0+ea.3",
                ),
                listOf("28.0.0.0+ea.20", "28.0.0.0", "28.0.0.0+1"),
            ).forEach { sequence ->
                should("order ${sequence.joinToString()} lowest first") {
                    // given: every version paired with each later one in the sequence
                    val pairs =
                        sequence.indices.flatMap { lowerIndex ->
                            sequence.drop(lowerIndex + 1).map { higher -> sequence[lowerIndex] to higher }
                        }

                    // when: ranking each pair both ways
                    val misranked =
                        pairs.filterNot { (lower, higher) ->
                            posted(lower).compareWith(posted(higher)) == Precedence.LOWER &&
                                posted(higher).compareWith(posted(lower)) == Precedence.HIGHER
                        }

                    // then: each earlier version ranks lower and each later version higher
                    misranked.shouldBeEmpty()
                }
            }

            listOf(
                ("27.0.0.0+35" to "28.0.0.0+ea.1") to "the core outranks early access in another series",
                ("22.1.0.1+r17" to "22.1.0.2+1") to "the core orders despite unknown build metadata",
            ).forEach { (versions, reason) ->
                val (lower, higher) = versions
                should("rank $lower below $higher because $reason") {
                    // given: two versions whose cores differ

                    // when: ranking them both ways
                    val rankings = posted(lower).compareWith(posted(higher)) to posted(higher).compareWith(posted(lower))

                    // then: the lower core ranks lower
                    rankings shouldBe (Precedence.LOWER to Precedence.HIGHER)
                }
            }

            listOf(
                "27.0.0+35" to "27.0.0.0+35",
                "17.0.20" to "17.0.20.0",
                "26.0.2.fx" to "26.0.2.0-fx",
                "25.0.0.0+01" to "25.0.0.0+1",
                "22.1.0.1+r17" to "22.1.0.1+r17",
            ).forEach { (storedVersion, postedVersion) ->
                should("rank stored $storedVersion equal to posted $postedVersion") {
                    // given: a stored row and a post that read the same

                    // when: ranking the post against the row
                    val precedence = posted(postedVersion).compareWith(stored(storedVersion))

                    // then: neither outranks the other
                    precedence shouldBe Precedence.EQUAL
                }
            }

            should("ignore the variant when ranking") {
                // given: a plain version and an fx version of equal core and build
                val plain = posted("25.0.4.0+1")
                val fx = posted("25.0.4.0-fx+1")

                // when: ranking one against the other
                val precedence = fx.compareWith(plain)

                // then: the variant does not order them
                precedence shouldBe Precedence.EQUAL
            }

            listOf(
                "22.1.0.1+r17" to "22.1.0.1+1",
                "22.1.0.1+r17" to "22.1.0.1",
                "22.1.0.1+r17" to "22.1.0.1+r21",
            ).forEach { (left, right) ->
                should("find $left and $right unordered") {
                    // given: two versions of equal core, one with unknown build metadata

                    // when: ranking them both ways
                    val rankings = posted(left).compareWith(posted(right)) to posted(right).compareWith(posted(left))

                    // then: neither direction can be ordered
                    rankings shouldBe (Precedence.UNORDERED to Precedence.UNORDERED)
                }
            }
        }

        context("a post beating a stored row") {

            listOf(
                ("25.0.4.1+1" to "25.0.4.0+9") to "it ranks higher",
                ("27.0.0.0+35" to "27.0.0+35") to "it reads equal under another spelling",
            ).forEach { (versions, reason) ->
                val (postedVersion, storedVersion) = versions
                should("let posted $postedVersion beat stored $storedVersion because $reason") {
                    // given: a post and a stored row of one series
                    val post = posted(postedVersion)
                    val row = stored(storedVersion)

                    // when: deciding whether the post beats the row
                    val result = post.beats(row)

                    // then: the post wins
                    result shouldBe true
                }
            }

            listOf(
                ("25.0.4.1+1" to "25.0.4.1+1") to "it is the same spelling",
                ("25.0.4.0+9" to "25.0.4.1+1") to "it ranks lower",
                ("22.1.0.1+1" to "22.1.0.1+r17") to "it cannot be ordered against the row",
            ).forEach { (versions, reason) ->
                val (postedVersion, storedVersion) = versions
                should("not let posted $postedVersion beat stored $storedVersion because $reason") {
                    // given: a post and a stored row of one series
                    val post = posted(postedVersion)
                    val row = stored(storedVersion)

                    // when: deciding whether the post beats the row
                    val result = post.beats(row)

                    // then: the row holds
                    result shouldBe false
                }
            }
        }
    })
