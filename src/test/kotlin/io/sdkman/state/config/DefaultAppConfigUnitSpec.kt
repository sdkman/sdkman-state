package io.sdkman.state.config

import io.kotest.core.spec.style.ShouldSpec
import io.sdkman.state.domain.model.CommunityAccount
import io.sdkman.state.support.shouldBeNone
import io.sdkman.state.support.shouldBeSome
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
    })
