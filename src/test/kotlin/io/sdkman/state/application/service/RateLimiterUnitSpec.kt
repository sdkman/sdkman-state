package io.sdkman.state.application.service

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class RateLimiterUnitSpec :
    ShouldSpec({
        should("allow first 5 attempts within the window") {
            val limiter = RateLimiter(enabled = true)
            val ip = "192.168.1.1"

            repeat(5) {
                limiter.checkAndRecord(ip) shouldBe false
            }
        }

        should("rate-limit the 6th attempt within the window") {
            val limiter = RateLimiter(enabled = true)
            val ip = "192.168.1.2"

            repeat(5) { limiter.checkAndRecord(ip) }

            limiter.checkAndRecord(ip) shouldBe true
        }

        should("track different IPs independently") {
            val limiter = RateLimiter(enabled = true)

            repeat(5) { limiter.checkAndRecord("ip-a") }

            limiter.checkAndRecord("ip-a") shouldBe true
            limiter.checkAndRecord("ip-b") shouldBe false
        }

        should("return false for unknown IP") {
            val limiter = RateLimiter(enabled = true)

            limiter.checkAndRecord("unknown") shouldBe false
        }

        should("keep rejecting attempts while the window stays full") {
            val limiter = RateLimiter(enabled = true)
            val ip = "192.168.1.3"

            // given: fill up the window
            repeat(5) { limiter.checkAndRecord(ip) }

            // when/then: every further attempt within the window is rejected
            repeat(10) {
                limiter.checkAndRecord(ip) shouldBe true
            }
        }

        should("cleanup removes expired entries") {
            val limiter = RateLimiter(enabled = true)

            limiter.checkAndRecord("cleanup-ip")
            limiter.checkAndRecord("cleanup-ip") shouldBe false

            limiter.cleanup()
            // entry still present (not expired)
            limiter.checkAndRecord("cleanup-ip") shouldBe false
        }

        should("never rate-limit the first attempt when disabled") {
            val limiter = RateLimiter(enabled = false)

            limiter.checkAndRecord("192.168.1.4") shouldBe false
        }

        should("never rate-limit even well beyond the threshold when disabled") {
            val limiter = RateLimiter(enabled = false)
            val ip = "192.168.1.5"

            repeat(20) {
                limiter.checkAndRecord(ip) shouldBe false
            }
        }
    })
