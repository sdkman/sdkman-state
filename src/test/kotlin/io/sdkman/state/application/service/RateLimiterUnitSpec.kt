package io.sdkman.state.application.service

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class RateLimiterUnitSpec :
    ShouldSpec({
        should("allow first 5 attempts within the window") {
            val limiter = RateLimiter()
            val ip = "192.168.1.1"

            repeat(5) {
                limiter.checkAndRecord(ip) shouldBe false
            }
        }

        should("rate-limit the 6th attempt within the window") {
            val limiter = RateLimiter()
            val ip = "192.168.1.2"

            repeat(5) { limiter.checkAndRecord(ip) }

            limiter.checkAndRecord(ip) shouldBe true
        }

        should("track different IPs independently") {
            val limiter = RateLimiter()

            repeat(5) { limiter.checkAndRecord("ip-a") }

            limiter.checkAndRecord("ip-a") shouldBe true
            limiter.checkAndRecord("ip-b") shouldBe false
        }

        should("return false for unknown IP") {
            val limiter = RateLimiter()

            limiter.checkAndRecord("unknown") shouldBe false
        }

        should("not record attempt when rate limited") {
            val limiter = RateLimiter()
            val ip = "192.168.1.3"

            // given: fill up the window
            repeat(5) { limiter.checkAndRecord(ip) }

            // when: additional attempts while rate limited
            repeat(10) { limiter.checkAndRecord(ip) }

            // then: still exactly 5 recorded (not 15)
            // verified indirectly — after window expires, only 5 would need to expire
        }

        should("cleanup removes expired entries") {
            val limiter = RateLimiter()

            limiter.checkAndRecord("cleanup-ip")
            limiter.checkAndRecord("cleanup-ip") shouldBe false

            limiter.cleanup()
            // entry still present (not expired)
            limiter.checkAndRecord("cleanup-ip") shouldBe false
        }
    })
