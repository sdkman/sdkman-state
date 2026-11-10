package io.sdkman.state.domain.service

import arrow.core.filterOption
import arrow.core.singleOrNone
import io.sdkman.state.domain.model.BuildMetadata
import io.sdkman.state.domain.model.JavaSeries
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.model.series
import java.math.BigInteger

sealed interface TagMove {
    val platform: Platform
    val tag: String

    data class Place(
        override val platform: Platform,
        override val tag: String,
        val version: String,
    ) : TagMove

    data class Remove(
        override val platform: Platform,
        override val tag: String,
    ) : TagMove
}

object JavaTagPlacement {
    private const val LTS_TAG = "lts"

    fun movesFor(
        rows: List<Version>,
        ltsFeature: Int,
    ): List<TagMove> =
        rows
            .groupBy(Version::platform)
            .flatMap { (platform, platformRows) -> ltsMoves(platform, plainHeads(platformRows), ltsFeature.toBigInteger()) }

    private fun plainHeads(rows: List<Version>): List<SeriesMember> =
        rows
            .map { row -> JavaVersion.read(row.version) }
            .filterOption()
            .map { reading -> reading.series }
            .filter(JavaSeries::isPlain)
            .distinct()
            .flatMap { series -> JavaSupersession.heads(JavaSupersession.members(series, rows)) }

    private fun ltsMoves(
        platform: Platform,
        plainHeads: List<SeriesMember>,
        ltsFeature: BigInteger,
    ): List<TagMove> =
        placeOnSoleHighest(
            platform,
            LTS_TAG,
            plainHeads.filter { head -> head.reading.series.feature == ltsFeature && head.isGenerallyAvailable },
        )

    private fun placeOnSoleHighest(
        platform: Platform,
        tag: String,
        candidates: List<SeriesMember>,
    ): List<TagMove> =
        JavaSupersession
            .heads(candidates)
            .singleOrNone()
            .filterNot { head -> head.row.tags.isSome { tags -> tag in tags } }
            .map { head -> TagMove.Place(platform, tag, head.row.version) }
            .toList()

    private val SeriesMember.isGenerallyAvailable: Boolean get() = reading.build !is BuildMetadata.EarlyAccess
}
