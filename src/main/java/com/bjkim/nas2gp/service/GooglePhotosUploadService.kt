package com.bjkim.nas2gp.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bjkim.nas2gp.MainActivity
import com.bjkim.nas2gp.auth.GoogleSignInHelper
import com.bjkim.nas2gp.db.AppDatabase
import com.bjkim.nas2gp.db.UploadedFile
import com.bjkim.nas2gp.repository.GooglePhotosRepository
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class GooglePhotosUploadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val isStopping = AtomicBoolean(false)

    private val repository = GooglePhotosRepository()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynologyDownloader::GPhotosWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification("Google Photos Upload Started", "Scanning files...", 0, 0, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Must specify service type on API 29+
                startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, notification)
            }

            if (job == null || job?.isActive == false) {
                job = serviceScope.launch {
                    processUploads()
                }
            }
            com.bjkim.nas2gp.service.BackupManager.appendLog("Service onStartCommand completed successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
            com.bjkim.nas2gp.service.BackupManager.appendLog("Fatal error starting foreground service: ${e.message}")
            stopSelf()
        }
        
        return START_STICKY
    }

    private suspend fun processUploads() {
        com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: started.")
        GooglePhotosUploadManager.setUploading(true)
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max
        
        try {
            // 1. Get Google Sign-In Account
            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Getting Google Sign-In Account...")
            val account = GoogleSignInHelper.getLastSignedInAccount(this)
            if (account == null) {
                GooglePhotosUploadManager.statusMessage.value = "Error: Not signed in or missing scopes"
                com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: account is null")
                return
            }

            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Got Account: ${account.email}. Fetching Access Token...")
            // 2. Obtain OAuth Access Token properly (Needs network roundtrip)
            val accessToken = withContext(Dispatchers.IO) {
                try {
                    val scopes = "oauth2:https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata " +
                                 "https://www.googleapis.com/auth/photoslibrary.edit.appcreateddata " +
                                 "https://www.googleapis.com/auth/photoslibrary.appendonly"
                    // requires account.account which is the android Account object if requested
                    GoogleAuthUtil.getToken(applicationContext, account.account!!, scopes)
                } catch (e: Exception) {
                    com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: getToken error: ${e.message}")
                    e.printStackTrace()
                    null
                }
            }

            if (accessToken == null) {
                GooglePhotosUploadManager.statusMessage.value = "Error: Failed to obtain Access Token"
                com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Failed to obtain Access Token")
                return
            }

            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Got Access Token! Scanning files...")
            val db = AppDatabase.getDatabase(this)
            val nasDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "nas")
            
            if (!nasDir.exists() || !nasDir.isDirectory) {
                GooglePhotosUploadManager.statusMessage.value = "NAS directory not found"
                com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: NAS directory not found: ${nasDir.absolutePath}")
                return
            }

            // 3. Scan for images and videos
            val filesToUpload = mutableListOf<File>()
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "raw", "dng")
            val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v")

            nasDir.walkTopDown()
                .onEnter { dir ->
                    // Log when we enter a directory to show we are scanning deep
                    com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Scanning directory -> ${dir.absolutePath}")
                    true // Return true to continue traversal
                }
                .onFail { file, exception ->
                    // Log if we hit a permission/access error for any specific file/dir
                    com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Error accessing ${file.absolutePath} -> ${exception.message}")
                }
                .forEach { file ->
                    if (file.isFile && !file.name.equals("folder.jpg", ignoreCase = true) 
                        && !file.name.equals("Thumbs.db", ignoreCase = true)
                        && file.name.substringAfterLast('.', "").lowercase() != "nfo"
                        && file.name != "@eaDir" // Avoid symlink loops / thumb dirs if synced
                    ) {
                        val ext = file.extension.lowercase()
                        if (ext in imageExtensions || ext in videoExtensions) {
                            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Found matching file: ${file.absolutePath}")
                            filesToUpload.add(file)
                        }
                    }
                }

            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Scanned directory [${nasDir.absolutePath}]. Found ${filesToUpload.size} matching files.")
            GooglePhotosUploadManager.statusMessage.value = "Found ${filesToUpload.size} matching files"
            
            var processed = 0
            var uploadedCount = 0
            var skippedCount = 0
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 4. Iterate and upload
            for (file in filesToUpload) {
                if (isStopping.get()) {
                    com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Stopping upload loop because isStopping=true")
                    break
                }
                
                processed++
                val progress = (processed * 100) / filesToUpload.size
                
                // Check if already uploaded
                val isUploaded = db.uploadedFileDao().isUploaded(file.absolutePath)
                if (isUploaded) {
                    skippedCount++
                    continue
                }

                GooglePhotosUploadManager.progress.value = progress
                GooglePhotosUploadManager.statusMessage.value = "Uploading $processed/${filesToUpload.size}: ${file.name}"
                
                val notification = createNotification(
                    "Google Photos Upload: $progress%", 
                    "$processed/${filesToUpload.size}: ${file.name}", 
                    100, progress, false
                )
                notificationManager.notify(2, notification)

                val mimeType = when (file.extension.lowercase()) {
                    in imageExtensions -> "image/${file.extension.lowercase()}"
                    in videoExtensions -> "video/${file.extension.lowercase()}"
                    else -> "application/octet-stream"
                }

                // 4a. Upload raw bytes
                try {
                    val totalBytes = file.length()
                    val inputStream = FileInputStream(file)
                    val uploadToken = repository.uploadBytesFromStream(accessToken, inputStream, mimeType, totalBytes)
                    inputStream.close()
                    
                    if (uploadToken != null) {
                        com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Bytes uploaded for ${file.name}, received uploadToken.")
                        // 4b. Create Media Item
                        val itemResult = repository.createMediaItem(accessToken, uploadToken, file.name)
                        if (itemResult?.status?.code == null || itemResult.status.code == 0) {
                             // Success
                             db.uploadedFileDao().insert(UploadedFile(
                                 filePath = file.absolutePath,
                                 uploadToken = uploadToken,
                                 mediaItemId = itemResult?.mediaItem?.id,
                                 uploadedAt = System.currentTimeMillis()
                             ))
                             uploadedCount++
                             com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Successfully uploaded ${file.name}")
                        } else {
                            // API Error creating item
                            GooglePhotosUploadManager.statusMessage.value = "Error creating item: ${itemResult.status.message}"
                            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Error creating item ${file.name}: ${itemResult.status.message}")
                        }
                    } else {
                        GooglePhotosUploadManager.statusMessage.value = "Failed to upload bytes for ${file.name}"
                        com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Failed to get uploadToken for ${file.name}")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    GooglePhotosUploadManager.statusMessage.value = "Exception uploading ${file.name}: ${e.message}"
                    com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Exception uploading ${file.name}: ${e.message}")
                }
            }

            GooglePhotosUploadManager.statusMessage.value = "Upload Complete. Uploaded: $uploadedCount, Skipped: $skippedCount"
            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads: Finished! Uploaded: $uploadedCount, Skipped: $skippedCount")
            
        } catch (e: Exception) {
            e.printStackTrace()
            GooglePhotosUploadManager.statusMessage.value = "Service Error: ${e.message}"
            com.bjkim.nas2gp.service.BackupManager.appendLog("processUploads caught exception: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
        } finally {
            GooglePhotosUploadManager.setUploading(false)
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            stopForeground(true)
            stopSelf()
        }
    }

    private fun createNotification(title: String, content: String, max: Int, progress: Int, indeterminate: Boolean): android.app.Notification {
        val channelId = "gphotos_upload_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Google Photos Upload",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .build()
    }
    
    override fun onDestroy() {
        isStopping.set(true)
        job?.cancel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }
}

