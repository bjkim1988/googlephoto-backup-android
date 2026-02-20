package com.bjkim.nas2gp.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface SynologyApi {

    @GET("webapi/auth.cgi")
    suspend fun login(
        @Query("api") api: String = "SYNO.API.Auth",
        @Query("version") version: Int = 6,
        @Query("method") method: String = "login",
        @Query("account") account: String,
        @Query("passwd") passwd: String,
        @Query("otp_code") otpCode: String? = null,
        @Query("enable_device_token") enableDeviceToken: String? = null,
        @Query("device_name") deviceName: String? = null,
        @Query("device_id") deviceId: String? = null,
        @Query("session") session: String = "FileStation",
        @Query("format") format: String = "cookie"
    ): Response<AuthResponse>

    @GET("webapi/entry.cgi")
    suspend fun listFiles(
        @Query("api") api: String = "SYNO.FileStation.List",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "list",
        @Query("folder_path") folderPath: String,
        @Query("additional") additional: String = "size,time",
        @Query("_sid") sid: String
    ): Response<ListResponse>

    @GET
    @Streaming
    suspend fun downloadFile(
        @Url url: String
    ): Response<ResponseBody>

    @GET
    @Streaming
    suspend fun downloadFileWithRange(
        @Url url: String,
        @retrofit2.http.Header("Range") range: String
    ): Response<ResponseBody>

    @GET("webapi/entry.cgi")
    suspend fun copyMove(
        @Query("api") api: String = "SYNO.FileStation.CopyMove",
        @Query("version") version: Int = 3,
        @Query("method") method: String = "start",
        @Query("path") path: String,
        @Query("dest_folder_path") destFolderPath: String,
        @Query("action") action: String = "move",
        @Query("overwrite") overwrite: Boolean = false,
        @Query("_sid") sid: String
    ): Response<CopyMoveStartResponse>

    @GET("webapi/entry.cgi")
    suspend fun copyMoveStatus(
        @Query("api") api: String = "SYNO.FileStation.CopyMove",
        @Query("version") version: Int = 3,
        @Query("method") method: String = "status",
        @Query("taskid") taskId: String,
        @Query("_sid") sid: String
    ): Response<CopyMoveStatusResponse>
    @GET("webapi/entry.cgi")
    suspend fun createFolder(
        @Query("api") api: String = "SYNO.FileStation.CreateFolder",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "create",
        @Query("folder_path") folderPath: String,
        @Query("name") name: String,
        @Query("force_parent") forceParent: Boolean = true,
        @Query("_sid") sid: String
    ): Response<CreateFolderResponse>
    @GET("webapi/entry.cgi")
    suspend fun startMD5(
        @Query("api") api: String = "SYNO.FileStation.MD5",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "start",
        @Query("file_path") filePath: String,
        @Query("_sid") sid: String
    ): Response<MD5StartResponse>

    @GET("webapi/entry.cgi")
    suspend fun statusMD5(
        @Query("api") api: String = "SYNO.FileStation.MD5",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "status",
        @Query("taskid") taskId: String,
        @Query("_sid") sid: String
    ): Response<MD5StatusResponse>

    @GET("webapi/entry.cgi")
    suspend fun delete(
        @Query("api") api: String = "SYNO.FileStation.Delete",
        @Query("version") version: Int = 2,
        @Query("method") method: String = "delete",
        @Query("path") path: String,
        @Query("recursive") recursive: Boolean = true,
        @Query("_sid") sid: String
    ): Response<DeleteResponse>
}

data class AuthResponse(
    val data: AuthData?,
    val success: Boolean,
    val error: ErrorData?
)

data class ErrorData(
    val code: Int
)

data class AuthData(
    val sid: String,
    val did: String? = null
)

data class ListResponse(
    val data: ListData?,
    val success: Boolean
)

data class ListData(
    val files: List<FileInfo>
)

data class FileInfo(
    val path: String,
    val name: String,
    val isdir: Boolean,
    val additional: AdditionalInfo? = null
)

data class AdditionalInfo(
    val size: Long = 0,
    val time: TimeInfo? = null
)

data class TimeInfo(
    val mtime: Long = 0
)

data class MD5StartResponse(
    val data: MD5StartData?,
    val success: Boolean,
    val error: ErrorData?
)

data class MD5StartData(
    val taskid: String
)

data class MD5StatusResponse(
    val data: MD5StatusData?,
    val success: Boolean
)

data class MD5StatusData(
    val finished: Boolean,
    val md5: String? = null
)

data class DeleteResponse(
    val success: Boolean,
    val error: ErrorData?
)



data class CopyMoveStartResponse(
    val data: CopyMoveData?,
    val success: Boolean,
    val error: ErrorData? // Reusing ErrorData
)

data class CopyMoveData(
    val taskid: String
)

data class CopyMoveStatusResponse(
    val data: CopyMoveStatusData?,
    val success: Boolean
)

data class CopyMoveStatusData(
    val finished: Boolean,
    val success: Boolean? = true // Sometimes accurate within data
)

data class CreateFolderResponse(
    val success: Boolean
)

