package io.sdkman.state.domain.service

import arrow.core.filterOption
import arrow.core.getOrElse
import io.sdkman.state.domain.model.JavaSeries
import io.sdkman.state.domain.model.JavaVersion
import io.sdkman.state.domain.model.Precedence
import io.sdkman.state.domain.model.Version
import io.sdkman.state.domain.model.series

data class SeriesMember(
    val row: Version,
    val reading: JavaVersion,
) {
    val isVisible: Boolean get() = row.visible.getOrElse { true }
}

object JavaSupersession {
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

    private fun visibleRowsOtherThan(
        post: JavaVersion,
        members: List<SeriesMember>,
    ): List<Version> =
        members
            .filter { member -> member.isVisible && member.row.version != post.spelling }
            .map(SeriesMember::row)
}
