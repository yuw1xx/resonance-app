package dev.yuwixx.resonance.data.network

import dev.yuwixx.resonance.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

// MusicBrainz blocks requests without a User-Agent naming the app and a contact. Only the
// MusicBrainz client uses this.
class MusicBrainzInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Resonance/${BuildConfig.VERSION_NAME} (+https://github.com/yuw1xx/resonance-app)")
            .build()
        return chain.proceed(request)
    }
}
