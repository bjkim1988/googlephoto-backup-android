package com.bjkim.nas2gp.network

import com.google.gson.annotations.SerializedName
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface GooglePhotosApi {

    /**
     * Uploads bytes to Google Photos to get an upload token.
     */
    @POST("v1/uploads")
    suspend fun uploadBytes(
        @Header("Authorization") authHeader: String,
        @Header("Content-type") contentType: String = "application/octet-stream",
        @Header("X-Goog-Upload-Content-Type") uploadContentType: String,
        @Header("X-Goog-Upload-Protocol") protocol: String = "raw",
        @Body fileBytes: RequestBody
    ): Response<okhttp3.ResponseBody>

    /**
     * Creates a MediaItem in Google Photos using the upload token.
     */
    @POST("v1/mediaItems:batchCreate")
    suspend fun batchCreateMediaItems(
        @Header("Authorization") authHeader: String,
        @Body request: BatchCreateMediaItemsRequest
    ): Response<BatchCreateMediaItemsResponse>

}

data class BatchCreateMediaItemsRequest(
    val newMediaItems: List<NewMediaItem>
)

data class NewMediaItem(
    val description: String?,
    val simpleMediaItem: SimpleMediaItem
)

data class SimpleMediaItem(
    val uploadToken: String,
    val fileName: String? = null
)

data class BatchCreateMediaItemsResponse(
    val newMediaItemResults: List<NewMediaItemResult>?
)

data class NewMediaItemResult(
    val uploadToken: String?,
    val status: Status?,
    val mediaItem: MediaItem?
)

data class Status(
    val message: String?,
    val code: Int?
)

data class MediaItem(
    val id: String?,
    val productUrl: String?,
    val baseUrl: String?,
    val mimeType: String?,
    val mediaMetadata: MediaMetadata?,
    val filename: String?
)

data class MediaMetadata(
    val creationTime: String?,
    val width: String?,
    val height: String?
)

