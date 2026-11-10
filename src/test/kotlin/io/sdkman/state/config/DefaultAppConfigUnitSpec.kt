package io.sdkman.state.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.config.ApplicationConfigurationException
import io.sdkman.state.domain.model.CommunityAccount
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
import io.sdkman.state.support.testApplicationConfigMissing
import io.sdkman.state.support.testApplicationConfigWithoutCommunityAccount

class DefaultAppConfigUnitSpec :
    ShouldSpec({

        val communityEmail = "community@sdkman.io"
        val communityPassword = "communitypassword"

        fun communityAccountOf(vararg community: Pair<String, String>) =
            DefaultAppConfig(
                testApplicationConfigWithoutCommunityAccount().apply {
                    community.forEach { (path, value) -> put(path, value) }
                },
            ).communityAccount

        fun javaLtsFeatureOf(feature: String) =
            DefaultAppConfig(
                testApplicationConfigWithoutCommunityAccount().apply { put("java.lts.feature", feature) },
            ).javaLtsFeature

        should("configure the community account when both email and password are set") {
            // when: both community keys are present
            val account =
                communityAccountOf(
                    "community.email" to communityEmail,
                    "community.password" to communityPassword,
                )

            // then: the account carries both values
            account shouldBeSome CommunityAccount(communityEmail, communityPassword)
        }

        should("treat the community account as absent when only the email is set") {
            // when: the password is missing
            val account = communityAccountOf("community.email" to communityEmail)

            // then: no account is configured
            account.shouldBeNone()
        }

        should("treat the community account as absent when only the password is set") {
            // when: the email is missing
            val account = communityAccountOf("community.password" to communityPassword)

            // then: no account is configured
            account.shouldBeNone()
        }

        should("treat the community account as absent when the password is blank") {
            // when: the password is blank
            val account =
                communityAccountOf(
                    "community.email" to communityEmail,
                    "community.password" to "   ",
                )

            // then: no account is configured
            account.shouldBeNone()
        }

        should("read the java LTS feature as an integer") {
            // when: the LTS feature is configured as 29
            val feature = javaLtsFeatureOf("29")

            // then: it reads as 29
            feature shouldBe 29
        }

        should("accept 11 as the lowest java LTS feature") {
            // when: the LTS feature is configured as 11
            val feature = javaLtsFeatureOf("11")

            // then: it reads as 11
            feature shouldBe 11
        }

        should("fail to start when the java LTS feature is missing") {
            // when: the LTS feature key is absent
            val failure =
                shouldThrow<ApplicationConfigurationException> {
                    DefaultAppConfig(testApplicationConfigMissing("java.lts.feature"))
                }

            // then: the failure names the missing key
            failure.message shouldContain "java.lts.feature"
        }

        should("fail to start when the java LTS feature is not an integer") {
            // when/then: a non-numeric LTS feature is rejected
            shouldThrow<NumberFormatException> { javaLtsFeatureOf("abc") }
        }

        should("fail to start when the java LTS feature is below 11") {
            // when/then: an LTS feature below 11 is rejected
            shouldThrowExactly<IllegalArgumentException> { javaLtsFeatureOf("10") }
        }
    })
