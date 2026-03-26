package io.sdkman.state.application.service

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ATTEMPTS = 5
private const val WINDOW_SECONDS = 60L

class RateLimiter {
    private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

    fun isRateLimited(clientIp: String): Boolean {
        val now = Instant.now()
        val windowStart = now.minusSeconds(WINDOW_SECONDS)
        val timestamps = attempts[clientIp] ?: return false
        synchronized(timestamps) {
            timestamps.removeAll { it.isBefore(windowStart) }
            return timestamps.size >= MAX_ATTEMPTS
        }
    }

    fun recordAttempt(clientIp: String) {
        val now = Instant.now()
        attempts.compute(clientIp) { _, existing ->
            val list = existing ?: mutableListOf()
            synchronized(list) {
                val windowStart = now.minusSeconds(WINDOW_SECONDS)
                list.removeAll { it.isBefore(windowStart) }
                list.add(now)
            }
            list
        }
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
