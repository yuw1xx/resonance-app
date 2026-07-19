package dev.yuwixx.resonance.data.service

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromePreloader @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5,  TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val warmed: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        fun preload(urls: List<String>) {
        val toWarm = urls.filterNot { it in warmed }
        if (toWarm.isEmpty()) return

        scope.launch {
            for (url in toWarm) {
                if (url in warmed) continue
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful || resp.code == 206) {
                            warmed += url
                            Log.d("NavidromePreloader", "Warmed: ${url.substringBefore('?')}")
                        } else {
                            Log.w("NavidromePreloader", "Warm failed ${resp.code}: ${url.substringBefore('?')}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("NavidromePreloader", "Warm error for ${url.substringBefore('?')}: ${e.message}")
                }
                delay(300)
            }
        }
    }

        fun reset() { warmed.clear() }
}
