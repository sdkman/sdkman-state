package io.sdkman.state.application.service

import arrow.core.getOrElse
import arrow.core.toOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ATTEMPTS = 5
private const val WINDOW_SECONDS = 60L

class RateLimiter {
    private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

    fun checkAndRecord(clientIp: String): Boolean {
        val now = Instant.now()
        val windowStart = now.minusSeconds(WINDOW_SECONDS)
        var rateLimited = false
        attempts.compute(clientIp) { _, existing ->
            val list = existing.toOption().getOrElse { mutableListOf() }
            list.removeAll { it.isBefore(windowStart) }
            rateLimited = list.size >= MAX_ATTEMPTS
            if (!rateLimited) {
                list.add(now)
            }
            list
        }
        return rateLimited
    }

    fun cleanup() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(WINDOW_SECONDS)
        val iterator = attempts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            synchronized(entry.value) {
                entry.value.removeAll { it.isBefore(windowStart) }
                if (entry.value.isEmpty()) {
                    iterator.remove()
                }
            }
        }
    }
}
