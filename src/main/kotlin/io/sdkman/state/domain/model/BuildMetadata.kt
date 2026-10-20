package io.sdkman.state.domain.model

import java.math.BigInteger

sealed interface BuildMetadata {
    data class EarlyAccess(
        val number: BigInteger,
    ) : BuildMetadata

    data object Absent : BuildMetadata

    data class Numeric(
        val numbers: List<BigInteger>,
    ) : BuildMetadata

    data class Unknown(
        val identifiers: List<String>,
    ) : BuildMetadata

    companion object {
        private const val EARLY_ACCESS = "ea"

        fun of(metadata: String): BuildMetadata =
            metadata.split('.').let { identifiers ->
                when {
                    isEarlyAccess(identifiers) -> EarlyAccess(identifiers.last().toBigInteger())
                    identifiers.all(::isNumeric) -> Numeric(identifiers.map { it.toBigInteger() })
                    else -> Unknown(identifiers)
                }
            }

        private fun isEarlyAccess(identifiers: List<String>): Boolean =
            identifiers.size == 2 && identifiers.first() == EARLY_ACCESS && isNumeric(identifiers.last())

        private fun isNumeric(identifier: String): Boolean = identifier.isNotEmpty() && identifier.all { it in '0'..'9' }
    }
}
