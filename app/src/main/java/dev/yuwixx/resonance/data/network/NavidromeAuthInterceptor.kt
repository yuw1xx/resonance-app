package dev.yuwixx.resonance.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest
import java.util.UUID

class NavidromeAuthInterceptor(
    private val username: String,
    private val password: String,
    private val clientName: String = "Resonance",
    private val apiVersion: String = "1.16.1",
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val salt = UUID.randomUUID().toString().replace("-", "").take(12)
        val token = md5("$password$salt")

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

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
