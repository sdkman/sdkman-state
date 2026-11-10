package io.sdkman.state.domain.service

import arrow.core.filterOption
import arrow.core.firstOrNone
import arrow.core.getOrElse
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
            .flatMap { (platform, platformRows) -> ltsMoves(platform, platformRows, ltsFeature.toBigInteger()) }

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
        rows: List<Version>,
        ltsFeature: BigInteger,
    ): List<TagMove> {
        val plainHeads = plainHeads(rows)
        val ltsFeatureHeads = plainHeads.filter { head -> head.reading.series.feature == ltsFeature && head.isGenerallyAvailable }
        return if (ltsFeatureHeads.isEmpty()) {
            keptLtsMoves(platform, rows, plainHeads)
        } else {
            placeOnSoleHighest(platform, LTS_TAG, ltsFeatureHeads)
        }
    }

    private fun keptLtsMoves(
        platform: Platform,
        rows: List<Version>,
        plainHeads: List<SeriesMember>,
    ): List<TagMove> =
        rows
            .firstOrNone { row -> row.holds(LTS_TAG) }
            .map { holder -> generallyAvailableHeadsInSeriesOf(holder, plainHeads) }
            .map { candidates ->
                if (candidates.isEmpty()) listOf(TagMove.Remove(platform, LTS_TAG)) else placeOnSoleHighest(platform, LTS_TAG, candidates)
            }.getOrElse { emptyList() }

    private fun generallyAvailableHeadsInSeriesOf(
        holder: Version,
        plainHeads: List<SeriesMember>,
    ): List<SeriesMember> =
        JavaVersion
            .read(holder.version)
            .map { reading -> plainHeads.filter { head -> head.reading.series == reading.series && head.isGenerallyAvailable } }
            .getOrElse { emptyList() }

    private fun placeOnSoleHighest(
        platform: Platform,
        tag: String,
        candidates: List<SeriesMember>,
    ): List<TagMove> =
        JavaSupersession
            .heads(candidates)
            .singleOrNone()
            .filterNot { head -> head.row.holds(tag) }
            .map { head -> TagMove.Place(platform, tag, head.row.version) }
            .toList()

    private val SeriesMember.isGenerallyAvailable: Boolean get() = reading.build !is BuildMetadata.EarlyAccess

    private fun Version.holds(tag: String): Boolean = tags.isSome { tags -> tag in tags }
}
