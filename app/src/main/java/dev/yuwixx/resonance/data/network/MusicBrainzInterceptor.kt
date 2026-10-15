package dev.yuwixx.resonance.data.network

import dev.yuwixx.resonance.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

// MusicBrainz's usage policy requires a descriptive User-Agent identifying the application and
// a contact point — requests without one are aggressively rate-limited or blocked outright.
// Scoped to MusicBrainz's own dedicated OkHttpClient (see NetworkModule) so this header doesn't
// leak into unrelated API calls.
class MusicBrainzInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Resonance/${BuildConfig.VERSION_NAME} (+https://github.com/yuw1xx/resonance-app)")
            .build()
        return chain.proceed(request)
    }
}
