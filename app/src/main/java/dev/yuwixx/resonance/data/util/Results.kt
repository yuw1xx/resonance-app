package dev.yuwixx.resonance.data.util

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] that lets coroutine cancellation through instead of reporting it as a failure. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
