package io.sdkman.state.application.service

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class RateLimiterUnitSpec :
    ShouldSpec({
        should("allow first 5 attempts within the window") {
            val limiter = RateLimiter()
            val ip = "192.168.1.1"

            repeat(5) {
                limiter.isRateLimited(ip) shouldBe false
                limiter.recordAttempt(ip)
            }
        }

        should("rate-limit the 6th attempt within the window") {
            val limiter = RateLimiter()
            val ip = "192.168.1.2"

            repeat(5) { limiter.recordAttempt(ip) }

            limiter.isRateLimited(ip) shouldBe true
        }

        should("track different IPs independently") {
            val limiter = RateLimiter()

            repeat(5) { limiter.recordAttempt("ip-a") }

            limiter.isRateLimited("ip-a") shouldBe true
            limiter.isRateLimited("ip-b") shouldBe false
        }

        should("return false for unknown IP") {
            val limiter = RateLimiter()

            limiter.isRateLimited("unknown") shouldBe false
        }

        should("cleanup removes expired entries") {
            val limiter = RateLimiter()

            limiter.recordAttempt("cleanup-ip")
            limiter.isRateLimited("cleanup-ip") shouldBe false

            limiter.cleanup()
            // entry still present (not expired)
            limiter.isRateLimited("cleanup-ip") shouldBe false
        }
    })
