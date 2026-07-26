package com.trellis.studio.data.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * NVIDIA TRELLIS image-to-3D via build.nvidia.com (NVCF).
 *
 * A request either completes synchronously (HTTP 200 with the result) or returns
 * HTTP 202 with an `NVCF-REQID` header, which is then polled on the status endpoint
 * until the result is ready.
 */
interface TrellisApi {

    @POST
    suspend fun generate(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: RequestBody,
        @Header("Accept") accept: String = "application/json"
    ): Response<ResponseBody>

    @GET
    suspend fun pollStatus(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("NVCF-POLL-SECONDS") pollSeconds: String = "10",
        @Header("Accept") accept: String = "application/json"
    ): Response<ResponseBody>
}
