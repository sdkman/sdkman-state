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

private data class PlatformRows(
    val platform: Platform,
    val rows: List<Version>,
) {
    val plainHeads: List<SeriesMember> =
        rows
            .map { row -> JavaVersion.read(row.version) }
            .filterOption()
            .map { reading -> reading.series }
            .filter(JavaSeries::isPlain)
            .distinct()
            .flatMap { series -> JavaSupersession.heads(JavaSupersession.members(series, rows)) }
}

object JavaTagPlacement {
    private const val LTS_TAG = "lts"

    fun movesFor(
        rows: List<Version>,
        ltsFeature: Int,
    ): List<TagMove> =
        rows
            .groupBy(Version::platform)
            .flatMap { (platform, platformRows) -> ltsMoves(PlatformRows(platform, platformRows), ltsFeature.toBigInteger()) }

    private fun ltsMoves(
        platform: PlatformRows,
        ltsFeature: BigInteger,
    ): List<TagMove> {
        val ltsFeatureHeads = platform.plainHeads.filter { head -> head.feature == ltsFeature && head.isGenerallyAvailable }
        return if (ltsFeatureHeads.isEmpty()) {
            keptLtsMoves(platform)
        } else {
            placeOnSoleHighest(platform.platform, LTS_TAG, ltsFeatureHeads)
        }
    }

    private fun keptLtsMoves(platform: PlatformRows): List<TagMove> =
        platform.rows
            .firstOrNone { row -> row.holds(LTS_TAG) }
            .map { holder -> placeOrRemove(platform, LTS_TAG, generallyAvailableHeadsInSeriesOf(holder, platform.plainHeads)) }
            .getOrElse { emptyList() }

    private fun generallyAvailableHeadsInSeriesOf(
        holder: Version,
        plainHeads: List<SeriesMember>,
    ): List<SeriesMember> =
        JavaVersion
            .read(holder.version)
            .map { reading -> plainHeads.filter { head -> head.reading.series == reading.series && head.isGenerallyAvailable } }
            .getOrElse { emptyList() }

    private fun placeOrRemove(
        platform: PlatformRows,
        tag: String,
        candidates: List<SeriesMember>,
    ): List<TagMove> =
        when {
            candidates.isNotEmpty() -> placeOnSoleHighest(platform.platform, tag, candidates)
            platform.rows.any { row -> row.holds(tag) } -> listOf(TagMove.Remove(platform.platform, tag))
            else -> emptyList()
        }

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

    private val SeriesMember.feature: BigInteger get() = reading.series.feature

    private val SeriesMember.isGenerallyAvailable: Boolean get() = reading.build !is BuildMetadata.EarlyAccess
}

private fun Version.holds(tag: String): Boolean = tags.isSome { tags -> tag in tags }
