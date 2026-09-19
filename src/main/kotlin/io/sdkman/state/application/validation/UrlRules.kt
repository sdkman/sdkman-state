package io.sdkman.state.application.validation

// Shared URL validation rules. A URL must be absolute, use the `https` scheme,
// and stay within `MAX_LENGTH` characters. Both `VersionRequestValidator`
// (version download URL) and the candidate registration path (`website_url`)
// reuse this single rule so the pattern and length bound are defined exactly once.
object UrlRules {
    const val MAX_LENGTH = 500
    val HTTPS_URL_PATTERN = Regex("^https://[a-zA-Z0-9.-]+(/.*)?$")
}
