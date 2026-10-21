package io.sdkman.state.domain.service

import arrow.core.Either
import arrow.core.Option
import arrow.core.Some
import arrow.core.filterOption
import arrow.core.firstOrNone
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import io.sdkman.state.domain.model.JavaSeries
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Precedence
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.model.series

data class SeriesMember(
    val row: Version,
    val reading: JavaVersion,
) {
    val isVisible: Boolean get() = row.isVisible
}

data class VendorDecision(
    val visible: Boolean,
    val hidden: List<Version>,
    val tagsToMove: List<String>,
)

data class Refusal(
    val head: String,
)

object JavaSupersession {
    private const val LATEST_TAG = "latest"
    private const val LTS_TAG = "lts"

    private val STORED_HIDDEN = VendorDecision(visible = false, hidden = emptyList(), tagsToMove = emptyList())

    fun members(
        series: JavaSeries,
        rows: List<Version>,
    ): List<SeriesMember> =
        rows
            .map { row -> JavaVersion.read(row.version).map { reading -> SeriesMember(row, reading) } }
            .filterOption()
            .filter { member -> member.reading.series == series }

    fun heads(members: List<SeriesMember>): List<SeriesMember> =
        members.filter(SeriesMember::isVisible).let { visible ->
            visible.filter { candidate ->
                visible.none { other -> other.reading.compareWith(candidate.reading) == Precedence.HIGHER }
            }
        }

    fun overrideHides(
        post: JavaVersion,
        rows: List<Version>,
    ): List<Version> = visibleRowsOtherThan(post, members(post.series, rows))

    fun decide(
        post: JavaVersion,
        rows: List<Version>,
    ): Either<Refusal, VendorDecision> {
        val members = members(post.series, rows)
        val heads = heads(members)
        val unorderedHead = heads.firstOrNone { head -> post.compareWith(head.reading) == Precedence.UNORDERED }
        return when {
            heads.isEmpty() || heads.any { head -> head.row.version == post.spelling } -> headDecision(post, members, rows).right()
            unorderedHead is Some -> Refusal(unorderedHead.value.row.version).left()
            heads.all { head -> post.beats(head.reading) } -> headDecision(post, members, rows).right()
            else -> STORED_HIDDEN.right()
        }
    }

    private fun headDecision(
        post: JavaVersion,
        members: List<SeriesMember>,
        rows: List<Version>,
    ): VendorDecision =
        VendorDecision(
            visible = true,
            hidden = visibleRowsOtherThan(post, members),
            tagsToMove = if (post.series.isPlain) tagsToMove(post, rows) else emptyList(),
        )

    private fun visibleRowsOtherThan(
        post: JavaVersion,
        members: List<SeriesMember>,
    ): List<Version> =
        members
            .filter { member -> member.isVisible && member.row.version != post.spelling }
            .map(SeriesMember::row)

    private fun tagsToMove(
        post: JavaVersion,
        rows: List<Version>,
    ): List<String> =
        listOf(post.series.feature.toString(), LATEST_TAG).filter { tag -> movesToHighestHead(post, holderOf(tag, rows)) } +
            listOf(LTS_TAG).filter { tag -> movesWithinSeries(post, holderOf(tag, rows)) }

    private fun holderOf(
        tag: String,
        rows: List<Version>,
    ): Option<Version> = rows.firstOrNone { row -> row.tags.isSome { tags -> tag in tags } }

    private fun movesToHighestHead(
        post: JavaVersion,
        holder: Option<Version>,
    ): Boolean =
        holder
            .flatMap { row -> JavaVersion.read(row.version) }
            .map { reading -> post.beats(reading) }
            .getOrElse { true }

    private fun movesWithinSeries(
        post: JavaVersion,
        holder: Option<Version>,
    ): Boolean =
        holder
            .filter { row -> row.version != post.spelling }
            .flatMap { row -> JavaVersion.read(row.version) }
            .isSome { reading -> reading.series == post.series }
}
