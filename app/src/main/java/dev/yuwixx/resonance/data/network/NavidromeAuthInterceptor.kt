package dev.yuwixx.resonance.data.network

import dev.yuwixx.resonance.data.util.md5Hex
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

class NavidromeAuthInterceptor(
    private val username: String,
    private val password: String,
    private val clientName: String = "Resonance",
    private val apiVersion: String = "1.16.1",
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5Hex("$password$salt")

        val url = chain.request().url.newBuilder()
            .addQueryParameter("u", username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", apiVersion)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
            .build()

        return chain.proceed(chain.request().newBuilder().url(url).build())
    }

}
