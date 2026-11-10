package io.sdkman.state.domain.service

import arrow.core.Option
import arrow.core.filterOption
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.singleOrNone
import arrow.core.toOption
import io.sdkman.state.domain.model.BuildMetadata
import io.sdkman.state.domain.model.JavaSeries
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.model.holderOf
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
    private const val LATEST_TAG = "latest"

    fun movesFor(
        rows: List<Version>,
        ltsFeature: Int,
    ): List<TagMove> {
        val platforms =
            rows
                .groupBy(Version::platform)
                .map { (platform, platformRows) -> PlatformRows(platform, platformRows) }
        val topFeature = platforms.flatMap(PlatformRows::plainHeads).maxOfOrNull { head -> head.feature }.toOption()
        return platforms.flatMap { platform -> movesOn(platform, topFeature, ltsFeature.toBigInteger()) }
    }

    private fun movesOn(
        platform: PlatformRows,
        topFeature: Option<BigInteger>,
        ltsFeature: BigInteger,
    ): List<TagMove> {
        val ltsMoves = ltsMoves(platform, ltsFeature)
        return ltsMoves + latestMoves(platform, topFeature, ltsFloor(platform, ltsMoves, ltsFeature))
    }

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
            .holderOf(LTS_TAG)
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

    private fun ltsFloor(
        platform: PlatformRows,
        ltsMoves: List<TagMove>,
        ltsFeature: BigInteger,
    ): BigInteger =
        ltsHolderAfter(platform, ltsMoves)
            .flatMap(JavaVersion::read)
            .map { reading -> reading.series.feature }
            .getOrElse { ltsFeature }

    private fun ltsHolderAfter(
        platform: PlatformRows,
        ltsMoves: List<TagMove>,
    ): Option<String> =
        if (ltsMoves.isEmpty()) {
            platform.rows.holderOf(LTS_TAG).map(Version::version)
        } else {
            ltsMoves.filterIsInstance<TagMove.Place>().firstOrNone().map(TagMove.Place::version)
        }

    private fun latestMoves(
        platform: PlatformRows,
        topFeature: Option<BigInteger>,
        ltsFloor: BigInteger,
    ): List<TagMove> =
        topFeature
            .filter { feature -> feature >= ltsFloor }
            .map { feature -> platform.plainHeads.filter { head -> head.feature == feature } }
            .getOrElse { emptyList() }
            .let { candidates -> placeOrRemove(platform, LATEST_TAG, candidates) }

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
