package com.example.test.service

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
import com.example.test.MainActivity
import com.example.test.model.BackupJob
import com.example.test.network.FileInfo
import com.example.test.utils.appendLog
import com.example.test.utils.saveToMediaStore
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BackupService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var job: Job? = null
    private val isStopping = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SynologyDownloader::BackupService")
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SynologyDownloader::BackupWifiLock")
        wifiLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Backup Service Started", "Waiting for queue...", 0, 0, true)
        startForeground(1, notification)

        if (job == null || job?.isActive == false) {
            job = serviceScope.launch {
                processQueue()
            }
        }
        
        return START_STICKY
    }

    private suspend fun processQueue() {
        BackupManager.setRunning(true)
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
        wifiLock?.acquire()
        
        try {
            while (!isStopping.get()) {
                val currentJob = BackupManager.popQueue()
                if (currentJob == null) {
                    // Queue empty, wait a bit or stop?
                    // Usually we stop service if queue is empty.
                    break
                }
                
                processJob(currentJob)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            BackupManager.appendLog("Service Error: ${e.message}")
        } finally {
            BackupManager.setRunning(false)
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            stopForeground(true)
            stopSelf()
        }
    }

    private suspend fun processJob(job: BackupJob) {
        val repository = BackupManager.repository ?: return
        val context = this
        val username = BackupManager.username
        
        BackupManager.appendLog("=== Service: Starting ${job.label} ===")
        BackupManager.statusMessage.value = "Running: ${job.label}"
        
        try {
            val freeSpace = Environment.getExternalStorageDirectory().freeSpace
            val reservedSpace = 10L * 1024 * 1024 * 1024 // 10GB
            val maxBytes = if (freeSpace > reservedSpace) freeSpace - reservedSpace else 0L

            if (maxBytes <= 0) {
                 BackupManager.statusMessage.value = "Error: Storage full (<10GB free)"
                 BackupManager.appendLog("Error: Insufficient storage (<10GB free)")
                 return
            }
            
            // Get files to process
            val candidateFiles = when (job) {
                is BackupJob.RecursiveBackup -> {
                    BackupManager.appendLog("Scanning subdirectories from ${job.sourcePath}...")
                    repository.listFilesRecursive(job.sourcePath) { scanningPath ->
                        BackupManager.statusMessage.value = "Scanning: $scanningPath"
                    }
                }
                is BackupJob.SelectedBackup -> job.files
            }
            
            BackupManager.appendLog("Found ${candidateFiles.size} candidate files")
            
            // Filter
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "svg", "raw", "cr2", "nef", "arw", "dng")
            val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")

            val filesToDownload = candidateFiles.filter { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase()
                ext in imageExtensions || ext in videoExtensions
            }
            
            var plannedDownloadBytes = 0L
            val realFilesToDownload = mutableListOf<FileInfo>()
            
            for (file in filesToDownload) {
                if (plannedDownloadBytes >= maxBytes) break
                val remoteSize = file.additional?.size ?: 0L
                
                // Local check logic mirroring MainActivity
                val parentPath = file.path.substringBeforeLast('/')
                val localSubDir = if (parentPath.length > job.sourcePath.length) {
                    "nas" + parentPath.substring(job.sourcePath.length)
                } else {
                    "nas"
                }
                
                // We assume check is done via utility but utility only returns size.
                // We'll stick to simple logic: check if file with same size exists in Downloads/nas/...
                // Using findFileInMediaStore is safer for Scoped Storage but slow in loop?
                // Actually findFileInMediaStore uses query, which might be slow.
                // Let's use File API for check if possible (on R+ we can read if we have MANAGE_EXTERNAL_STORAGE, otherwise restricted).
                // The app has MANAGE_EXTERNAL_STORAGE permission requested in Manifest.
                // So File() check should work.
                
                val localDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), localSubDir)
                val targetFile = File(localDir, file.name)
                
                if (targetFile.exists() && targetFile.length() == remoteSize) continue
                
                realFilesToDownload.add(file)
                plannedDownloadBytes += remoteSize
            }
            
             BackupManager.appendLog("Plan: ${realFilesToDownload.size} files, ${plannedDownloadBytes / 1024} KB")
             
             var currentDownloadedBytes = 0L
             var filesDone = 0
             val backupStartTime = System.currentTimeMillis()
             var lastNotifyTime = 0L
             val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

             for ((index, file) in realFilesToDownload.withIndex()) {
                 if (currentDownloadedBytes >= maxBytes) break
                 
                 val remoteSize = file.additional?.size ?: 0L
                 val sizeStr = when {
                    remoteSize >= 1024 * 1024 -> String.format("%.2f MB", remoteSize / (1024.0 * 1024.0))
                    remoteSize >= 1024 -> String.format("%.2f KB", remoteSize / 1024.0)
                    else -> "$remoteSize B"
                 }
                 
                 // Update Status
                 val elapsedTime = System.currentTimeMillis() - backupStartTime
                 var etaStr = ""
                 if (currentDownloadedBytes > 0 && elapsedTime > 2000) {
                     val bytesPerSec = (currentDownloadedBytes.toDouble() * 1000) / elapsedTime
                     val remainingBytes = plannedDownloadBytes - currentDownloadedBytes
                     if (bytesPerSec > 0) {
                         val remainingSeconds = (remainingBytes / bytesPerSec).toLong()
                         etaStr = if (remainingSeconds < 60) " ${remainingSeconds}s" 
                                  else if (remainingSeconds < 3600) " ${remainingSeconds/60}m ${remainingSeconds%60}s"
                                  else " ${remainingSeconds/3600}h"
                     }
                 }
                 
                 val progress = ((index + 1) * 100) / realFilesToDownload.size
                 BackupManager.statusMessage.value = "Downloading ${index+1}/${realFilesToDownload.size} ($etaStr): ${file.name}"
                 BackupManager.progress.value = progress
                 
                 if (System.currentTimeMillis() - lastNotifyTime > 1000) {
                     val notification = createNotification(
                         "Backup: $progress% ($etaStr left)", 
                         "${index + 1}/${realFilesToDownload.size}: ${file.name}", 
                         100, progress, false
                     )
                     notificationManager.notify(1, notification)
                     lastNotifyTime = System.currentTimeMillis()
                 }
                 
                 // Download
                 BackupManager.appendLog("Downloading: ${file.name} ($sizeStr)")
                 val stream = repository.downloadFile(file.path) { err ->
                     CoroutineScope(Dispatchers.Main).launch {
                         BackupManager.appendLog("Stream Error ${file.name}: $err")
                     }
                 }
                 
                 if (stream != null) {
                    val fileParentPath = file.path.substringBeforeLast('/')
                    val localSubDir = if (fileParentPath.length > job.sourcePath.length) {
                        "nas" + fileParentPath.substring(job.sourcePath.length)
                    } else {
                        "nas"
                    }
                    val mtime = file.additional?.time?.mtime ?: 0L
                    val lastModifiedMs = if (mtime > 0) mtime * 1000L else null
                    
                    val success = saveToMediaStore(context, stream, file.name, localSubDir, remoteSize, lastModifiedMs) { err ->
                        CoroutineScope(Dispatchers.Main).launch {
                            BackupManager.appendLog("Save Error: $err")
                        }
                    }
                    
                    if (success) {
                        currentDownloadedBytes += remoteSize
                        filesDone++
                        
                        if (job.moveOnNas) {
                            // Move on NAS
                            var destFolder = "/homes/$username/google_photo_backup"
                            if (file.path.startsWith("/photo/")) {
                                val parentPath = file.path.substringBeforeLast('/')
                                if (parentPath.length > "/photo".length) {
                                    val subPath = parentPath.substring("/photo".length)
                                    destFolder += subPath
                                }
                            } else if (file.path.startsWith(destFolder)) {
                                 // Already in dest
                                 continue
                            }
                            
                            repository.createFolder(destFolder)
                            val moveSuccess = repository.moveFile(file.path, destFolder)
                            
                            if (moveSuccess) {
                                BackupManager.appendLog(" - Move to $destFolder: Success")
                                // Verify move
                                delay(1000)
                                var destFiles = repository.listFiles(destFolder)
                                var movedFileExists = destFiles.any { it.name == file.name }
                                if (!movedFileExists) {
                                    delay(1000)
                                    destFiles = repository.listFiles(destFolder)
                                    movedFileExists = destFiles.any { it.name == file.name }
                                }
                                
                                val sourceParentPath = file.path.substringBeforeLast('/')
                                val sourceFiles = repository.listFiles(sourceParentPath)
                                val sourceFileExists = sourceFiles.any { it.name == file.name }
                                
                                if (movedFileExists && sourceFileExists) {
                                    val delResult = repository.deleteFile(file.path)
                                    if (delResult == "Success") {
                                        BackupManager.appendLog(" - Auto-Fix: Source deleted")
                                    } else {
                                        BackupManager.appendLog(" - Auto-Fix Failed: $delResult")
                                    }
                                }
                            } else {
                                BackupManager.appendLog(" - Move: Failed")
                            }
                        } else {
                            BackupManager.appendLog(" - Download Only: Skipped Move")
                        }
                    } else {
                        BackupManager.appendLog(" - Save failed locally")
                    }
                 }
             }
             
             // Cleanup empty dirs
             if (job is BackupJob.RecursiveBackup) {
                 BackupManager.appendLog("Checking for empty directories...")
                 val dirsToCheck = mutableListOf<String>()
                 val dirQueue = ArrayDeque<String>()
                 dirQueue.add(job.sourcePath)
                 while (dirQueue.isNotEmpty()) {
                     val dir = dirQueue.removeFirst()
                     val items = repository.listFiles(dir)
                     for (item in items) {
                         if (item.isdir) {
                             dirsToCheck.add(item.path)
                             dirQueue.add(item.path)
                         }
                     }
                 }
                 var deletedDirs = 0
                 for (dir in dirsToCheck.reversed()) {
                     val contents = repository.listFiles(dir)
                     if (contents.isEmpty()) {
                         val result = repository.deleteFile(dir)
                         if (result == "Success") deletedDirs++
                     }
                 }
                 if (deletedDirs > 0) BackupManager.appendLog("Cleaned up $deletedDirs empty directories")
             }
             
             BackupManager.appendLog("Job Done. Processed $filesDone files.")
             
        } catch (e: Exception) {
            e.printStackTrace()
            BackupManager.appendLog("Job Failed: ${e.message}")
        }
    }

    private fun createNotification(title: String, content: String, max: Int, progress: Int, indeterminate: Boolean): android.app.Notification {
        val channelId = "backup_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Backup Progress",
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        super.onDestroy()
    }
}
