package io.sdkman.validation

object CandidateLoader {
    private const val CANDIDATES_RESOURCE = "/candidates.txt"

    val allowedCandidates: List<String> by lazy {
        loadCandidates()
    }

    private fun loadCandidates(): List<String> {
        val resource =
            this::class.java.getResourceAsStream(CANDIDATES_RESOURCE)
                ?: throw IllegalStateException("Cannot find $CANDIDATES_RESOURCE in classpath")

        return resource.bufferedReader().use { reader ->
            reader
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }
}
