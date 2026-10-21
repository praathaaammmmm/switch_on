package com.switchon.customer.data.remote

import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()

/** Adds the guest session token to every call except the one that creates it. */
class AuthInterceptor(private val session: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val token = session.token
        if (token == null || req.url.encodedPath.endsWith("/guest-sessions")) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On a 401, opens a fresh session for the same installation (so the same customer and history) and retries once.
 * Covers expired tokens and a reset development database.
 */
class SessionAuthenticator(private val session: SessionStore, private val baseUrl: String) : Authenticator {
    private val plain = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.endsWith("/guest-sessions")) return null
        if (response.priorResponse != null) return null // already retried once
        synchronized(this) {
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
            // Another call may have refreshed while we waited.
            if (session.token != null && session.token != sent) return response.request.withToken(session.token!!)
            val body = apiJson.encodeToString(GuestSessionRequest(session.installationId)).toRequestBody(JSON)
            val res = runCatching { plain.newCall(Request.Builder().url(baseUrl + "v1/guest-sessions").post(body).build()).execute() }.getOrNull() ?: return null
            res.use {
                if (!it.isSuccessful) return null
                val s = apiJson.decodeFromString<GuestSessionResponse>(it.body!!.string())
                session.token = s.token
                session.customerId = s.profile.customerId
                return response.request.withToken(s.token)
            }
        }
    }

    private fun Request.withToken(t: String) = newBuilder().header("Authorization", "Bearer $t").build()
}

fun buildApi(baseUrl: String, session: SessionStore, debugLogging: Boolean): SwitchOnApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(session))
        .authenticator(SessionAuthenticator(session, baseUrl))
        .apply { if (debugLogging) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }) }
        .build()
    return Retrofit.Builder().baseUrl(baseUrl).client(client).addConverterFactory(apiJson.asConverterFactory(JSON)).build()
        .create(SwitchOnApi::class.java)
}
