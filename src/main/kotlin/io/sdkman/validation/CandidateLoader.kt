package io.sdkman.validation

import arrow.core.Option
import arrow.core.getOrElse

object CandidateLoader {
    private const val CANDIDATES_RESOURCE = "/candidates.txt"

    val allowedCandidates: List<String> by lazy {
        loadCandidates()
    }

    private fun loadCandidates(): List<String> {
        val resource =
            Option
                .fromNullable(this::class.java.getResourceAsStream(CANDIDATES_RESOURCE))
                .getOrElse { error("Cannot find $CANDIDATES_RESOURCE in classpath") }

        return resource.bufferedReader().use { reader ->
            reader
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }
}
