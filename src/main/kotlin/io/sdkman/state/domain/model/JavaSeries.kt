package io.sdkman.state.domain.model

import arrow.core.Option
import java.math.BigInteger

private const val FEATURE_COMPONENT = 0
private const val INTERIM_COMPONENT = 1

enum class ReleaseLine {
    FEATURE,
    INTERIM,
}

data class JavaSeries(
    val feature: BigInteger,
    val releaseLine: ReleaseLine,
    val variant: Option<String>,
) {
    val isPlain: Boolean get() = variant.isNone()
}

val JavaVersion.series: JavaSeries
    get() =
        JavaSeries(
            feature = core[FEATURE_COMPONENT],
            releaseLine = if (core[INTERIM_COMPONENT] == BigInteger.ZERO) ReleaseLine.FEATURE else ReleaseLine.INTERIM,
            variant = variant,
        )
