package com.trellis.studio.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Pollinations FLUX image generation — free, no API key.
 * GET https://image.pollinations.ai/prompt/{prompt}
 */
interface PollinationsApi {

    @Streaming
    @GET("prompt/{prompt}")
    suspend fun generateImage(
        @Path("prompt") prompt: String,
        @Query("width") width: Int = 1024,
        @Query("height") height: Int = 1024,
        @Query("model") model: String = "flux",
        @Query("nologo") noLogo: Boolean = true,
        @Query("seed") seed: Long
    ): Response<ResponseBody>
}
