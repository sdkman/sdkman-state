package io.sdkman.state.application.validation

// Shared URL validation rules. A URL must be an absolute `https` URL over a
// host of alphanumerics, dots and hyphens, with an optional path. Both
// `VersionRequestValidator` (version download URL) and
// `CandidateRequestValidator` (candidate website URL) reuse this single rule so
// the pattern is defined exactly once. Business rule 9 of the candidate
// registry spec requires the two to stay identical.
object UrlRules {
    val HTTPS_URL_PATTERN = Regex("^https://[a-zA-Z0-9.-]+(/.*)?$")
}
