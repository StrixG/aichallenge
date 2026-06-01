package me.obrekht.wishu

import android.app.Application
import me.obrekht.wishu.data.WishDatabase
import me.obrekht.wishu.network.DeepSeekApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

class WishuApplication : Application() {

    val database by lazy { WishDatabase.getDatabase(this) }

    val deepSeekApi: DeepSeekApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
                        .build()
                )
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.deepseek.com/")
            .client(client)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekApi::class.java)
    }
}
