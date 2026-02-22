package com.bjkim.nas2gp.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bjkim.nas2gp.network.BatchCreateMediaItemsRequest
import com.bjkim.nas2gp.network.GooglePhotosApi
import com.bjkim.nas2gp.network.NewMediaItem
import com.bjkim.nas2gp.network.NewMediaItemResult
import com.bjkim.nas2gp.network.SimpleMediaItem
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.source

class GooglePhotosRepository {

    private val api: GooglePhotosApi

    init {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.BODY) })
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://photoslibrary.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(GooglePhotosApi::class.java)
    }

    /**
     * Uploads the file bytes to Google Photos and returns an upload token.
     */
    suspend fun uploadBytes(accessToken: String, file: File, mimeType: String): String? {
        return try {
            val authHeader = "Bearer $accessToken"
            val requestBody = file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            
            val response = api.uploadBytes(
                authHeader = authHeader,
                uploadContentType = mimeType,
                fileBytes = requestBody
            )
            
            if (response.isSuccessful) {
                response.body()?.string()
            } else {
                val errorMsg = "Upload Bytes Failed: ${response.code()} ${response.message()} Body: ${response.errorBody()?.string()}"
                Log.e("GooglePhotosRepo", errorMsg)
                com.bjkim.nas2gp.service.BackupManager.appendLog(errorMsg)
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bjkim.nas2gp.service.BackupManager.appendLog("Upload Bytes Exception: ${e.message}")
            null
        }
    }

    /**
     * Uploads bytes from an InputStream to Google Photos and returns an upload token.
     */
    suspend fun uploadBytesFromStream(accessToken: String, inputStream: InputStream, mimeType: String, totalBytes: Long): String? {
        return try {
            val authHeader = "Bearer $accessToken"
            
            val requestBody = object : RequestBody() {
                override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

                override fun contentLength(): Long = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(1024 * 64) // 64KB chunk
                    var uploaded = 0L
                    var read: Int

                    inputStream.use { input ->
                        while (input.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            uploaded += read
                            
                            // Update current file progress
                            com.bjkim.nas2gp.service.GooglePhotosUploadManager.currentFileBytesUploaded.value = uploaded
                            com.bjkim.nas2gp.service.GooglePhotosUploadManager.progress.value = ((uploaded.toDouble() / totalBytes) * 100).toInt()
                        }
                    }
                }
            }
            
            val response = api.uploadBytes(
                authHeader = authHeader,
                uploadContentType = mimeType,
                fileBytes = requestBody
            )
            
            if (response.isSuccessful) {
                response.body()?.string()
            } else {
                val errorMsg = "Upload Stream Failed: ${response.code()} ${response.message()} Body: ${response.errorBody()?.string()}"
                Log.e("GooglePhotosRepo", errorMsg)
                com.bjkim.nas2gp.service.BackupManager.appendLog(errorMsg)
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bjkim.nas2gp.service.BackupManager.appendLog("Upload Stream Exception: ${e.message}")
            null
        }
    }

    /**
     * Registers an uploaded token as a valid MediaItem in the Google Photos library.
     */
    suspend fun createMediaItem(accessToken: String, uploadToken: String, fileName: String): NewMediaItemResult? {
        return try {
            val authHeader = "Bearer $accessToken"
            val request = BatchCreateMediaItemsRequest(
                newMediaItems = listOf(
                    NewMediaItem(
                        description = fileName,
                        simpleMediaItem = SimpleMediaItem(
                            uploadToken = uploadToken,
                            fileName = fileName
                        )
                    )
                )
            )

            val response = api.batchCreateMediaItems(
                authHeader = authHeader,
                request = request
            )

            if (response.isSuccessful && response.body()?.newMediaItemResults?.isNotEmpty() == true) {
                response.body()?.newMediaItemResults?.firstOrNull()
            } else {
                val errorMsg = "Batch Create Failed: ${response.code()} ${response.message()} Body: ${response.errorBody()?.string()}"
                Log.e("GooglePhotosRepo", errorMsg)
                com.bjkim.nas2gp.service.BackupManager.appendLog(errorMsg)
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.bjkim.nas2gp.service.BackupManager.appendLog("Batch Create Exception: ${e.message}")
            null
        }
    }
}

