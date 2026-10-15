package dev.yuwixx.resonance.data.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Serializes calls to a rate-limited external API (MusicBrainz, Discogs) behind a minimum
// interval between requests. The Mutex both prevents concurrent callers from racing ahead of
// each other and gives a single serialization point where delay() can enforce the interval.
class ApiRateLimiter(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastCallAt = 0L

    suspend fun <T> withRateLimit(block: suspend () -> T): T = mutex.withLock {
        val wait = minIntervalMs - (System.currentTimeMillis() - lastCallAt)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastCallAt = System.currentTimeMillis()
        }
    }
}
