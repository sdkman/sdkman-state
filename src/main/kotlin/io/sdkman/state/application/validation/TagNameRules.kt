package io.sdkman.state.application.validation

object TagNameRules {
    const val MAX_LENGTH = 50
    val PATTERN = Regex("^[a-zA-Z0-9]([a-zA-Z0-9._-]{0,48}[a-zA-Z0-9])?$")

    fun validate(
        field: String,
        tag: String,
    ): List<ValidationError> =
        when {
            tag.isBlank() ->
                listOf(InvalidTagError(field, "Tag must not be blank"))

            tag.length > MAX_LENGTH ->
                listOf(InvalidTagError(field, "Tag must not exceed $MAX_LENGTH characters"))

            !PATTERN.matches(tag) ->
                listOf(
                    InvalidTagError(
                        field,
                        "Tag must contain only alphanumeric characters, dots, hyphens, and underscores, " +
                            "and must start and end with an alphanumeric character",
                    ),
                )

            else -> emptyList()
        }
}
