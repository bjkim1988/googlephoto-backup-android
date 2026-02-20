package com.bjkim.nas2gp.repository

import android.util.Log
import com.bjkim.nas2gp.network.AuthResponse
import com.bjkim.nas2gp.network.FileInfo
import com.bjkim.nas2gp.network.ListResponse
import com.bjkim.nas2gp.network.SynologyApi
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SynologyRepository {

    private var api: SynologyApi? = null
    private var sid: String? = null
    private var baseUrl: String = ""

    fun init(baseUrl: String) {
        this.baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        val client = getUnsafeOkHttpClient().newBuilder()
            .addInterceptor(HttpLoggingInterceptor().apply { setLevel(HttpLoggingInterceptor.Level.HEADERS) })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(this.baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(SynologyApi::class.java)
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    data class LoginResult(
        val message: String,
        val did: String? = null,
        val needsOtp: Boolean = false
    )

    suspend fun login(
        account: String, 
        passwd: String, 
        otpCode: String? = null,
        enableDeviceToken: Boolean = false,
        deviceName: String? = null,
        deviceId: String? = null
    ): LoginResult {
        return try {
            val response = api?.login(
                account = account, 
                passwd = passwd, 
                otpCode = otpCode,
                enableDeviceToken = if (enableDeviceToken) "yes" else null,
                deviceName = deviceName,
                deviceId = deviceId
            )
            if (response?.isSuccessful == true) {
                val body = response.body()
                if (body?.success == true) {
                    sid = body.data?.sid
                    LoginResult("Success", did = body.data?.did)
                } else {
                    val errorCode = body?.error?.code ?: -1
                    val needsOtp = errorCode == 403 || errorCode == 404
                    LoginResult(
                        "Failed: API Error Code $errorCode (${getErrorMessage(errorCode)})",
                        needsOtp = needsOtp
                    )
                }
            } else {
                LoginResult("Failed: HTTP ${response?.code()} ${response?.message()}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LoginResult("Error: ${e.message}")
        }
    }

    private fun getErrorMessage(code: Int): String {
        return when (code) {
            400 -> "No such account or incorrect password / General Error"
            401 -> "Account disabled / No such file or directory"
            402 -> "Permission denied"
            403 -> "2FA Failed (Wrong Code)"
            404 -> "2FA Failed (Code Expired) / File not found"
            405 -> "App privileges disabled"
            406 -> "Not logged in"
            407 -> "Action not permitted"
            408 -> "No such file or directory"
            409 -> "Not supported"
            410 -> "Failed to delete file(s)"
            411 -> "Read-only file system"
            421 -> "System busy"
            1000 -> "Failed to list files"
            1001 -> "Failed to list files (Folder not found)"
            1002 -> "Failed to list files (Permission denied)"
            1100 -> "Failed to create folder"
            1101 -> "Folder quota exceeded"
            1200 -> "Failed into rename/move"
            1400 -> "Failed to extract"
            1800 -> "No Read/Write Permission"
            2000 -> "Upload failed"
            2001 -> "File overwrite not allowed"
            else -> "Unknown Error ($code)"
        }
    }

    suspend fun listFiles(folderPath: String): List<FileInfo> {
        return try {
            if (sid == null) return emptyList()
            val response = api?.listFiles(folderPath = folderPath, additional = "[\"size\",\"time\"]", sid = sid!!)
            if (response?.isSuccessful == true && response.body()?.success == true) {
                response.body()?.data?.files?.filter { 
                    it.name != "#recycle" && 
                    !it.path.contains("/#recycle/") &&
                    !it.name.endsWith("-poster.jpg", ignoreCase = true)
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun listFilesRecursive(folderPath: String, onProgress: (suspend (String) -> Unit)? = null): List<FileInfo> {
        val allFiles = mutableListOf<FileInfo>()
        val queue = ArrayDeque<String>()
        queue.add(folderPath)
        
        while (queue.isNotEmpty()) {
            val currentPath = queue.removeFirst()
            onProgress?.invoke(currentPath)
            val items = listFiles(currentPath)
            for (item in items) {
                if (item.isdir) {
                    queue.add(item.path)
                } else {
                    allFiles.add(item)
                }
            }
        }
        return allFiles
    }

    suspend fun createFolder(fullPath: String): Boolean {
        return try {
            if (sid == null) return false
            if (!fullPath.contains("/")) return false
            
            val parentPath = fullPath.substringBeforeLast('/')
            val folderName = fullPath.substringAfterLast('/')
            
            if (parentPath.isEmpty() || folderName.isEmpty()) return false

            // Using JSON format as per API spec for createFolder
            val response = api?.createFolder(
                folderPath = "[\"$parentPath\"]", 
                name = "[\"$folderName\"]",
                forceParent = true, 
                sid = sid!!
            )
            
            response?.isSuccessful == true && response.body()?.success == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getMD5(path: String): String? {
        return try {
            if (sid == null) {
                println("MD5 Debug: SID is null")
                return null
            }
            println("MD5 Debug: Starting for $path")
            val startResponse = api?.startMD5(filePath = path, sid = sid!!)
            
            if (startResponse?.isSuccessful == true) {
                val body = startResponse.body()
                if (body?.success == true) {
                    val taskId = body.data?.taskid
                    println("MD5 Debug: Started. TaskID=$taskId")
                    
                    if (taskId == null) return null
                    
                    var retries = 0
                    while (retries < 60) { // Max 30s
                        delay(500)
                        val statusResponse = api?.statusMD5(taskId = taskId, sid = sid!!)
                        if (statusResponse?.isSuccessful == true) {
                            val statusBody = statusResponse.body()
                            if (statusBody?.success == true) {
                                if (statusBody.data?.finished == true) {
                                    println("MD5 Debug: Finished. MD5=${statusBody.data.md5}")
                                    return statusBody.data.md5
                                }
                            } else {
                                println("MD5 Debug: Status success=false")
                            }
                        } else {
                            println("MD5 Debug: Status HTTP Error ${statusResponse?.code()}")
                        }
                        retries++
                    }
                    println("MD5 Debug: Timed out waiting for MD5")
                    return null
                } else {
                    println("MD5 Debug: Start success=false. Error=${body?.error?.code}")
                    return null
                }
            } else {
                println("MD5 Debug: Start HTTP Error ${startResponse?.code()}")
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("MD5 Debug: Exception ${e.message}")
            return null
        }
    }

    suspend fun deleteFile(path: String): String {
        return try {
            if (sid == null) return "Not Logged In"
            
            val response = api?.delete(path = path, sid = sid!!)
            if (response?.isSuccessful == true) {
                 if (response.body()?.success == true) {
                     "Success"
                 } else {
                     val code = response.body()?.error?.code ?: -1
                     "Failed: API $code (${getErrorMessage(code)})"
                 }
            } else {
                 "Failed: HTTP ${response?.code()} ${response?.message()}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }

    suspend fun downloadFile(path: String, onError: (String) -> Unit = {}): InputStream? {
        return try {
            if (sid == null) {
                onError("Not logged in (SID is null)")
                return null
            }
            // Construct download URL manually
            val encodePath = java.net.URLEncoder.encode(path, "UTF-8")
            val downloadUrl = "${baseUrl}webapi/entry.cgi?api=SYNO.FileStation.Download&version=2&method=download&path=$encodePath&mode=open&_sid=$sid"
            
            val response = api?.downloadFile(downloadUrl)
            if (response?.isSuccessful == true) {
                val input = response.body()?.byteStream()
                if (input == null) onError("Response body is empty")
                input
            } else {
                onError("HTTP ${response?.code()} ${response?.message()}")
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError("Exception: ${e.message}")
            null
        }
    }

    suspend fun moveFile(sourcePath: String, destFolderPath: String): Boolean {
        return try {
            if (sid == null) return false
            val response = api?.copyMove(
                path = sourcePath,
                destFolderPath = destFolderPath,
                action = "move",
                overwrite = true, 
                sid = sid!!
            )
            
            if (response?.isSuccessful == true && response.body()?.success == true) {
                val taskId = response.body()?.data?.taskid
                if (!taskId.isNullOrEmpty()) {
                    // Poll for status
                    var retries = 0
                    while (retries < 20) { // Max 10 seconds
                        delay(500)
                        val statusResponse = api?.copyMoveStatus(taskId = taskId, sid = sid!!)
                        if (statusResponse?.isSuccessful == true) {
                             val statusData = statusResponse.body()?.data
                             if (statusData?.finished == true) {
                                 return true
                             }
                        }
                        retries++
                    }
                    Log.e("SynologyRepo", "Move timed out for task $taskId")
                    return false
                } else {
                    // Completed synchronously
                    return true
                }
            } else {
                 Log.e("SynologyRepo", "Move failed to start: ${response?.body()?.error?.code}")
                 return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    suspend fun downloadPartial(path: String, start: Long, end: Long): ByteArray? {
        return try {
            if (sid == null) return null
            val encodePath = java.net.URLEncoder.encode(path, "UTF-8")
            val downloadUrl = "${baseUrl}webapi/entry.cgi?api=SYNO.FileStation.Download&version=2&method=download&path=$encodePath&mode=open&_sid=$sid"
            
            val rangeHeader = "bytes=$start-$end"
            val response = api?.downloadFileWithRange(downloadUrl, rangeHeader)
            if (response?.isSuccessful == true) {
                response.body()?.bytes()
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun compareFilesPartial(path1: String, path2: String, size: Long): Boolean {
        if (size <= 0) return false
        
        return try {
             coroutineScope {
                val headJob1 = async { downloadPartial(path1, 0, 99) }
                val headJob2 = async { downloadPartial(path2, 0, 99) }
                
                val head1 = headJob1.await()
                val head2 = headJob2.await()
                
                if (head1 == null || head2 == null || !head1.contentEquals(head2)) {
                    return@coroutineScope false
                }
                
                if (size > 100) {
                    val start = if (size > 100) size - 100 else 0
                    val end = size - 1
                    
                    val tailJob1 = async { downloadPartial(path1, start, end) }
                    val tailJob2 = async { downloadPartial(path2, start, end) }
                    
                    val tail1 = tailJob1.await()
                    val tail2 = tailJob2.await()
                    
                    if (tail1 == null || tail2 == null || !tail1.contentEquals(tail2)) {
                        return@coroutineScope false
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun scanSubnet(subnet: String, port: Int = 5001): List<String> = coroutineScope {
        // subnet should be like "192.168.0"
        val jobs = (1..254).map { i ->
            async {
                try {
                    val ip = "$subnet.$i"
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), 500)
                    socket.close()
                    ip
                } catch (e: Exception) {
                    null
                }
            }
        }
        jobs.mapNotNull { it.await() }
    }
}

