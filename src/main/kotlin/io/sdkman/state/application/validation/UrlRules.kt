package io.sdkman.state.application.validation

object UrlRules {
    const val MAX_LENGTH = 500
    val HTTPS_URL_PATTERN = Regex("^https://[a-zA-Z0-9.-]+(/.*)?$")
}
