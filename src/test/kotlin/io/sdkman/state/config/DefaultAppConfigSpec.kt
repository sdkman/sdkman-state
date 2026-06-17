package io.sdkman.state.config

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

class DefaultAppConfigSpec :
    ShouldSpec({

        // jwt.secret has no default and is read lazily, but include it so the config is well-formed.
        fun baseConfig() =
            MapApplicationConfig().apply {
                put("jwt.secret", "test-secret")
            }

        context("strictVersionCandidates") {
            should("expose the configured set of opted-in candidates") {
                val config =
                    baseConfig().apply {
                        put("version.validation.strict-candidates", listOf("java", "scala"))
                    }

                DefaultAppConfig(config).strictVersionCandidates shouldBe setOf("java", "scala")
            }

            should("default to { java } when no candidates are configured") {
                DefaultAppConfig(baseConfig()).strictVersionCandidates shouldBe setOf("java")
            }
        }
    })
