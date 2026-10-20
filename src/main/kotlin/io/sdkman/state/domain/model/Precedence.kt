package io.sdkman.state.domain.model

enum class Precedence {
    HIGHER,
    LOWER,
    EQUAL,
    UNORDERED,
    ;

    companion object {
        fun ofComparison(comparison: Int): Precedence =
            when {
                comparison > 0 -> HIGHER
                comparison < 0 -> LOWER
                else -> EQUAL
            }
    }
}
