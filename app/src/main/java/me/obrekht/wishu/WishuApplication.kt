package me.obrekht.wishu

import android.app.Application
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import me.obrekht.wishu.data.SettingsRepository
import me.obrekht.wishu.data.WishDatabase
import java.io.IOException
import me.obrekht.wishu.network.DeepSeekApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

class WishuApplication : Application() {

    val database by lazy { WishDatabase.getDatabase(this) }
    val settingsRepository by lazy { SettingsRepository(this) }

    // Timeouts + retry + auth, shared by every client variant.
    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            // OkHttp does not auto-retry non-idempotent POSTs, so a stale pooled connection
            // closed by the server surfaces as "unexpected end of stream". Retry on a fresh one.
            .addInterceptor { chain ->
                var lastError: IOException? = null
                repeat(3) { attempt ->
                    try {
                        return@addInterceptor chain.proceed(chain.request())
                    } catch (e: IOException) {
                        lastError = e
                    }
                }
                throw lastError ?: IOException("request failed")
            }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
                        .build()
                )
            }

    // BODY logging in debug. Used by Retrofit for the short wishlist calls.
    val httpClient: OkHttpClient by lazy {
        baseClientBuilder()
            .apply {
                if (BuildConfig.DEBUG) {
                    addNetworkInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
                    )
                }
            }
            .build()
    }

    // No BODY logging: a network-level body-logging interceptor buffers the whole
    // response, which would defeat token-by-token SSE streaming. Keeps auth + retry + timeouts.
    val streamingHttpClient: OkHttpClient by lazy { baseClientBuilder().build() }

    val deepSeekApi: DeepSeekApi by lazy {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekApi::class.java)
    }
}
