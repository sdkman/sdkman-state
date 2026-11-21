package io.sdkman.state.application.validation

import io.sdkman.state.domain.model.Platform
import io.sdkman.state.domain.model.Version

object PlatformShapeRules {
    fun validate(
        version: Version,
        storedPlatforms: List<Platform>,
    ): List<ValidationError> =
        when {
            version.platform in storedPlatforms -> emptyList()
            version.platform == Platform.UNIVERSAL && storedPlatforms.isNotEmpty() ->
                listOf(PlatformShapeError(version = version.version))

            Platform.UNIVERSAL in storedPlatforms -> listOf(PlatformShapeError(version = version.version))
            else -> emptyList()
        }
}
