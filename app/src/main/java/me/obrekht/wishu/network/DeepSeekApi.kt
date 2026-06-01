package me.obrekht.wishu.network

import retrofit2.http.Body
import retrofit2.http.POST

interface DeepSeekApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): ChatResponse
}
