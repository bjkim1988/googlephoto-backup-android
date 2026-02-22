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
import com.bjkim.nas2gp.model.BackupJob
import com.bjkim.nas2gp.network.FileInfo
import com.bjkim.nas2gp.utils.appendLog
import com.bjkim.nas2gp.utils.saveToMediaStore
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SynologyDownloader::BackupWifiLock")
        } else {
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SynologyDownloader::BackupWifiLock")
        }
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
            if (e is CancellationException) throw e
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
            
            if (job is BackupJob.SplitFiles) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                var lastStatsUpdateTime = 0L
                com.bjkim.nas2gp.utils.splitLargeFiles(this, job.filePaths, 
                    onLog = { msg ->
                       BackupManager.appendLog(msg)
                       BackupManager.statusMessage.value = "Splitting: $msg"
                    },
                    onProgress = { progress ->
                       BackupManager.progress.value = progress
                       
                       val currentTime = System.currentTimeMillis()
                       if (currentTime - lastStatsUpdateTime > 3000) { // Update stats every 3 seconds
                           val externalDir = Environment.getExternalStorageDirectory()
                           BackupManager.storageStats.value = Pair(externalDir.totalSpace, externalDir.freeSpace)
                           lastStatsUpdateTime = currentTime
                       }

                       val notification = createNotification("Splitting large files", "$progress%", 100, progress, false)
                       notificationManager.notify(1, notification)
                    }
                )
                BackupManager.appendLog("=== Split Task Finished ===")
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
                else -> emptyList() // Should not happen with current sealed class
            }
            
            BackupManager.appendLog("Found ${candidateFiles.size} candidate files")
            
            // Filter junk and non-media
            val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "svg", "raw", "cr2", "nef", "arw", "dng")
            val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")

            val filesToProcess = candidateFiles.filter { file ->
                if (file.name.equals("folder.jpg", ignoreCase = true) || file.name.endsWith("-poster.jpg", ignoreCase = true)) {
                    return@filter false
                }
                val ext = file.name.substringAfterLast('.', "").lowercase()
                ext in imageExtensions || ext in videoExtensions
            }
            
            if (filesToProcess.size < candidateFiles.size) {
                BackupManager.appendLog(" - Filtered out ${candidateFiles.size - filesToProcess.size} non-media or junk files")
            }
            
            var potentialDownloadBytes = 0L
            var alreadyDownloadedBytes = 0L
            
            for (file in filesToProcess) {
                val remoteSize = file.additional?.size ?: 0L
                
                val parentPath = file.path.substringBeforeLast('/')
                val localSubDir = if (file.path.startsWith("/photo")) {
                    val sub = parentPath.substringAfter("/photo", "")
                    if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                } else if (file.path.startsWith("/homes/$username/google_photo_backup")) {
                    val sub = parentPath.substringAfter("/homes/$username/google_photo_backup", "")
                    if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                } else if (file.path.contains("google_photo_backup")) {
                     val sub = parentPath.substringAfter("google_photo_backup", "")
                     if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                } else {
                    "nas"
                }
                
                val localDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), localSubDir)
                val targetFile = File(localDir, file.name)
                
                if (targetFile.exists() && targetFile.length() == remoteSize) {
                    alreadyDownloadedBytes += remoteSize
                } else {
                    potentialDownloadBytes += remoteSize
                }
            }
            
             val totalScopeBytes = filesToProcess.sumOf { it.additional?.size ?: 0L }
             BackupManager.totalBytesTarget.value = totalScopeBytes
             BackupManager.totalBytesProcessed.value = 0L
             BackupManager.etaString.value = ""
             
             var currentProcessedBytes = 0L
             var filesDone = 0
             val backupStartTime = System.currentTimeMillis()
             var lastNotifyTime = 0L
             val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
             var lastStorageReportBytes = 0L

             fun updateGlobalProgress(increment: Long = 0L) {
                 currentProcessedBytes += increment
                 BackupManager.totalBytesProcessed.value = currentProcessedBytes
                 
                 val now = System.currentTimeMillis()
                 if (now - lastNotifyTime > 1000) {
                     lastNotifyTime = now
                     val elapsedMs = now - backupStartTime
                     val totalTarget = BackupManager.totalBytesTarget.value
                     val processed = currentProcessedBytes
                     
                     // Speed calculation (bytes per second)
                     val speed = if (elapsedMs > 0) processed.toDouble() / (elapsedMs / 1000.0) else 0.0
                     val remainingBytes = totalTarget - processed
                     val etaSec = if (speed > 0) (remainingBytes / speed).toLong() else 0L
                     
                     val etaText = if (etaSec > 0) {
                         val h = etaSec / 3600
                         val m = (etaSec % 3600) / 60
                         val s = etaSec % 60
                         if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
                     } else "--:--"
                     BackupManager.etaString.value = etaText
                     
                     val progressPercent = if (totalTarget > 0) (processed * 100 / totalTarget).toInt() else 0
                     BackupManager.progress.value = progressPercent
                     
                     val processedStr = formatBytes(processed)
                     val totalStr = formatBytes(totalTarget)
                     val status = "Backing up: $processedStr / $totalStr (ETA: $etaText)"
                     BackupManager.statusMessage.value = status
                     
                     val notification = NotificationCompat.Builder(this@BackupService, "backup_channel")
                         .setContentTitle("NAS Backup: $filesDone/${filesToProcess.size}")
                         .setContentText(status)
                         .setSmallIcon(android.R.drawable.stat_sys_download)
                         .setProgress(100, progressPercent, false)
                         .build()
                     notificationManager.notify(1, notification)
                 }
             }

             for ((index, file) in filesToProcess.withIndex()) {
                 if (isStopping.get()) break
                 
                 val remoteSize = file.additional?.size ?: 0L
                 
                 // Local directory resolution
                 val fileParentPath = file.path.substringBeforeLast('/')
                 val localSubDir = if (file.path.startsWith("/photo")) {
                     val sub = fileParentPath.substringAfter("/photo", "")
                     if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                 } else if (file.path.startsWith("/homes/$username/google_photo_backup")) {
                     val sub = fileParentPath.substringAfter("/homes/$username/google_photo_backup", "")
                     if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                 } else if (file.path.contains("google_photo_backup")) {
                     val sub = fileParentPath.substringAfter("google_photo_backup", "")
                     if (sub.startsWith("/")) "nas$sub" else if (sub.isNotEmpty()) "nas/$sub" else "nas"
                 } else {
                     "nas"
                 }

                 val localDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), localSubDir)
                 val targetFile = File(localDir, file.name)
                 
                var success = false
                
                // Check if download is needed
                if (targetFile.exists() && targetFile.length() == remoteSize) {
                    BackupManager.appendLog(" - [Local Match] ${file.name} (Proceeding to Move check)")
                    updateGlobalProgress(remoteSize)
                    success = true
                } else {
                    // Need to download. Check storage first.
                    val freeSpace = Environment.getExternalStorageDirectory().freeSpace
                    if (freeSpace < reservedSpace + remoteSize) {
                        BackupManager.appendLog(" - Skipped ${file.name}: Insufficient storage")
                        continue
                    }

                    val sizeStr = when {
                       remoteSize >= 1024 * 1024 -> String.format("%.2f MB", remoteSize / (1024.0 * 1024.0))
                       remoteSize >= 1024 -> String.format("%.2f KB", remoteSize / 1024.0)
                       else -> "$remoteSize B"
                    }
                    
                    BackupManager.statusMessage.value = "Downloading ${index+1}/${filesToProcess.size}: ${file.name}"
                    
                    var attempt = 1
                    while (!success && !isStopping.get()) {
                       if (attempt > 1) {
                           BackupManager.appendLog("Retry Attempt $attempt for ${file.path}...")
                           kotlinx.coroutines.delay(3000)
                       }

                       if (attempt == 1) {
                            BackupManager.appendLog("Downloading: ${file.path} ($sizeStr)")
                       }

                       val stream = repository.downloadFile(file.path) { err ->
                            BackupManager.appendLog("Stream Error: $err")
                       }

                       if (stream != null) {
                           val mtime = file.additional?.time?.mtime ?: 0L
                           val lastModifiedMs = if (mtime > 0) mtime * 1000L else null
                           
                           success = saveToMediaStore(context, stream, file.name, localSubDir, remoteSize, lastModifiedMs, atomicReplace = true, onLog = { msg ->
                               BackupManager.appendLog(msg)
                           }) { bytesCopied ->
                               updateGlobalProgress(bytesCopied)
                           }
                       }
                       if (!success) attempt++
                       if (attempt > 3) break // Max retries
                    }
                }

                if (success) {
                    if (currentProcessedBytes - lastStorageReportBytes > 100 * 1024 * 1024) {
                        val dir = Environment.getExternalStorageDirectory()
                        BackupManager.storageStats.value = Pair(dir.totalSpace, dir.freeSpace)
                        lastStorageReportBytes = currentProcessedBytes
                    }
                    filesDone++
                    
                    if (job.moveOnNas) {
                        // Move on NAS
                        val backupRoot = "/homes/$username/google_photo_backup"
                        val parentPath = file.path.substringBeforeLast('/')
                        val destFolder = backupRoot + parentPath
                        
                        // Check if file is already in destination or backup area
                        if (file.path.startsWith(backupRoot)) {
                            BackupManager.appendLog(" - [Skip Move] File already in backup area: ${file.name}")
                        } else {
                            BackupManager.appendLog(" - [Moving] ${file.path} -> $destFolder")
                            repository.createFolder(destFolder)
                            val moveSuccess = repository.moveFile(file.path, destFolder)
                            
                            if (moveSuccess) {
                                BackupManager.appendLog(" - Move Success: ${file.name}")
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
                                        BackupManager.appendLog(" - Cleaned up source copy: ${file.name}")
                                    }
                                }
                            } else {
                                BackupManager.appendLog(" - [Move Failed] ${file.name}")
                            }
                        }
                    } else {
                        // Download Only mode
                    }
                } else {
                    BackupManager.appendLog(" - [Error] Failed to process ${file.name} locally")
                }
            }
             
             // Cleanup empty dirs and junk files
             BackupManager.appendLog("Checking for potential empty directories and junk files...")
             BackupManager.statusMessage.value = "Cleaning up directories..."
             
             // 1. Local Cleanup (Always run to keep Downloads/nas clean)
             val localNasDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "nas")
             if (localNasDir.exists()) {
                 val localDeleted = com.bjkim.nas2gp.utils.deleteEmptyDirectories(localNasDir)
                 if (localDeleted > 0) BackupManager.appendLog("Cleaned up $localDeleted empty local directories")
             }
             
             // 2. NAS Cleanup (Only if we moved files)
             if (job.moveOnNas) {
                 val dirsToCheck = mutableSetOf<String>()
                 //derive dirs from files we touched
                 for (file in candidateFiles) {
                    var currentPath = file.path.substringBeforeLast('/')
                    // Add all ancestor paths up to sourcePath
                    while (currentPath.length >= job.sourcePath.length && currentPath.startsWith(job.sourcePath)) {
                        dirsToCheck.add(currentPath)
                        if (currentPath == job.sourcePath) break
                        currentPath = currentPath.substringBeforeLast('/')
                    }
                 }
                 // Also add sourcePath itself
                 dirsToCheck.add(job.sourcePath)

                 var deletedDirs = 0
                 var deletedJunkFiles = 0
                 val sortedDirs = dirsToCheck.sortedByDescending { it.length }
    
                 for (dir in sortedDirs) {
                     if (isStopping.get()) break
                     
                     BackupManager.statusMessage.value = "Cleaning NAS dir: ${dir.substringAfterLast('/')}"
                     val contents = repository.listFiles(dir)
                     
                     // Auto-delete junk files
                     val junkFiles = contents.filter { !it.isdir && (
                         it.name.equals("Thumbs.db", ignoreCase = true) || 
                         it.name.substringAfterLast('.', "").lowercase() == "nfo" ||
                         it.name.equals("folder.jpg", ignoreCase = true) ||
                         it.name.endsWith("-poster.jpg", ignoreCase = true)
                     ) }
                     for (junk in junkFiles) {
                         val result = repository.deleteFile(junk.path)
                         if (result == "Success") deletedJunkFiles++
                     }
                     
                     // Check if empty after deleting junk files
                     val remainingContents = contents.filter { it.isdir || !junkFiles.contains(it) }
                     val isEffectivelyEmpty = remainingContents.isEmpty() || remainingContents.all { it.isdir && it.name == "@eaDir" }
                     
                     if (isEffectivelyEmpty) {
                         val result = repository.deleteFile(dir)
                         if (result == "Success") {
                             deletedDirs++
                             BackupManager.appendLog(" - Deleted empty NAS dir: $dir")
                         }
                     }
                 }
    
                 if (deletedJunkFiles > 0) BackupManager.appendLog("Cleaned up $deletedJunkFiles junk files on NAS (.nfo, folder.jpg, Thumbs.db, etc.)")
                 if (deletedDirs > 0) BackupManager.appendLog("Cleaned up $deletedDirs empty directories on NAS")
             }
             
             BackupManager.appendLog("Job Done. Processed $filesDone files.")
             BackupManager.statusMessage.value = "Finished"
             BackupManager.progress.value = 100
             
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            BackupManager.appendLog("Job Failed: ${e.message}")
        }
    }

    private fun formatBytes(bytes: Long): String {
        return if (bytes >= 0.9 * 1024 * 1024 * 1024) {
            String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        } else {
            "${bytes / (1024 * 1024)} MB"
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

