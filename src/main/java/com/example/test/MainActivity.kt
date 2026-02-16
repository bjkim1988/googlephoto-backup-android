package com.example.test

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.example.test.network.FileInfo
import com.example.test.repository.SynologyRepository
import com.example.test.ui.theme.TestTheme
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import java.io.FileOutputStream
import java.io.InputStream

import android.app.Activity
import androidx.activity.compose.BackHandler

class MainActivity : ComponentActivity() {

    private val repository = SynologyRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            TestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SynologyDownloaderApp(repository)
                }
            }
        }
    }
}

sealed class BackupJob {
    abstract val label: String
    data class RecursiveBackup(val sourcePath: String) : BackupJob() {
        override val label = "Full Backup: $sourcePath"
    }
    data class SelectedBackup(val files: List<FileInfo>, val sourcePath: String) : BackupJob() {
        override val label = "Selected: ${files.size} files"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SynologyDownloaderApp(repository: SynologyRepository) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    
    // Plain prefs for non-sensitive data
    val sharedPref = remember { context.getSharedPreferences("SynologyPrefs", Context.MODE_PRIVATE) }
    
    val savedUser = remember { sharedPref.getString("last_user", "") ?: "" }
    val savedHost = remember { sharedPref.getString("last_host", "https://your-nas-address:5001") ?: "https://your-nas-address:5001" }

    var host by remember { mutableStateOf(savedHost) }
    var username by remember { mutableStateOf(savedUser) }
    var password by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var sourcePath by remember { mutableStateOf("/photo") }
    var statusMessage by remember { mutableStateOf("Ready") }
    var fileList by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var debugLog by remember { mutableStateOf("") }
    var isLoggedIn by remember { mutableStateOf(false) }
    
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<FileInfo>>(emptySet()) }
    
    // Login step: 1 = NAS Address, 2 = Username, 3 = Password, 4 = OTP
    var loginStep by remember { mutableStateOf(
        if (savedHost.isNotBlank() && savedHost != "https://your-nas-address:5001" && savedUser.isNotBlank()) 3 else 1
    ) }
    var rememberDevice by remember { mutableStateOf(false) }
    val savedDeviceId = remember { sharedPref.getString("device_id", null) }
    var deviceId by remember { mutableStateOf(savedDeviceId) }
    
    // NAS discovery
    var discoveredNas by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    
    // Backup queue
    var backupQueue by remember { mutableStateOf<List<BackupJob>>(emptyList()) }
    var isBackupRunning by remember { mutableStateOf(false) }
    var currentBackupLabel by remember { mutableStateOf("") }
    var activeBackupJob by remember { mutableStateOf<Job?>(null) }
    
    // Leftover files handling
    var leftoverFiles by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var showLeftoverDialog by remember { mutableStateOf(false) }
    var showStorageErrorDialog by remember { mutableStateOf(false) }
    
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Handle permissions
    }

    // Load last session log on startup
    LaunchedEffect(Unit) {
        val lastLog = withContext(Dispatchers.IO) { readLastLog(context) }
        if (lastLog.isNotEmpty()) {
            debugLog = "--- LAST SESSION LOG (Potential Crash Context) ---\n$lastLog\n--------------------------\n"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    val refreshList = { path: String ->
        scope.launch {
            statusMessage = "Listing files..."
            withContext(Dispatchers.IO) {
                try {
                    val files = repository.listFiles(path)
                    withContext(Dispatchers.Main) {
                        fileList = files
                        statusMessage = "Files listed: ${files.size}"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Error listing files: ${e.message}"
                    }
                }
            }
        }
    }

    // Handle back button
    BackHandler(enabled = isLoggedIn) {
        if (sourcePath == "/photo" || sourcePath == "/") {
            (context as? Activity)?.finish()
        } else {
            val lastSlash = sourcePath.lastIndexOf('/')
            val parentPath = if (lastSlash > 0) sourcePath.substring(0, lastSlash) else "/"
            sourcePath = parentPath
            refreshList(parentPath)
        }
    }

    // Queue processor
    LaunchedEffect(Unit) {
        snapshotFlow { backupQueue to isBackupRunning }
            .collect { (queue, running) ->
                if (queue.isNotEmpty() && !running) {
            val job = queue.first()
            backupQueue = backupQueue.drop(1)
            isBackupRunning = true
            currentBackupLabel = job.label
            
            activeBackupJob = scope.launch {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SynologyDownloader::Backup")
                
                // Setup Notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val notificationId = 1
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel("backup_channel", "Backup Progress", android.app.NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows backup progress"
                    }
                    notificationManager.createNotificationChannel(channel)
                }
                
                // Build intent to launch app on click
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val notificationBuilder = NotificationCompat.Builder(context, "backup_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Synology Backup")
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                
                if (wakeLock != null) {
                    wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours max
                    appendLog(context, "WakeLock acquired")
                }

                try {
                val maxBytes = 50 * 1024 * 1024L
                
                val jobSourcePath = when (job) {
                    is BackupJob.RecursiveBackup -> job.sourcePath
                    is BackupJob.SelectedBackup -> job.sourcePath
                }
                
                debugLog += "=== Queue: Starting ${job.label} ===\n"
                appendLog(context, "=== Queue: Starting ${job.label} ===")
                statusMessage = "Running: ${job.label}"
                
                withContext(Dispatchers.IO) {
                val freeSpace = Environment.getExternalStorageDirectory().freeSpace
                val reservedSpace = 10L * 1024 * 1024 * 1024 // 10GB
                val maxBytes = if (freeSpace > reservedSpace) freeSpace - reservedSpace else 0L
                
                if (maxBytes <= 0) {
                    withContext(Dispatchers.Main) {
                        statusMessage = "Error: Storage full (<10GB free)"
                        showStorageErrorDialog = true
                        debugLog += "Error: Insufficient storage (Require > 10GB free)\n"
                    }
                    throw Exception("Insufficient storage (<10GB free)")
                }

                withContext(Dispatchers.Main) {
                    val freeGB = String.format("%.2f", freeSpace / (1024.0 * 1024.0 * 1024.0))
                    val limitMB = String.format("%.2f", maxBytes / (1024.0 * 1024.0))
                    debugLog += "Storage: ${freeGB}GB free. Reserving 10GB. Batch Limit: ${limitMB}MB\n"
                }
                
                // Get files to process
                val candidateFiles = when (job) {
                    is BackupJob.RecursiveBackup -> {
                        withContext(Dispatchers.Main) {
                            debugLog += "Scanning all subdirectories from ${job.sourcePath}...\n"
                            appendLog(context, "Scanning all subdirectories from ${job.sourcePath}...")
                        }
                        val files = repository.listFilesRecursive(job.sourcePath) { scanningPath ->
                            withContext(Dispatchers.Main) {
                                statusMessage = "Scanning: $scanningPath"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            debugLog += "Found ${files.size} files total\n"
                            appendLog(context, "Found ${files.size} files total")
                        }
                        files
                    }
                    is BackupJob.SelectedBackup -> job.files
                }

                // Identify files to process (images/videos) vs leftovers
                val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "svg", "raw", "cr2", "nef", "arw", "dng")
                val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")

                val filesToDownload = mutableListOf<FileInfo>()
                val nonMediaFiles = mutableListOf<FileInfo>()
                
                var plannedDownloadBytes = 0L
                
                for (file in candidateFiles) {
                     val ext = file.name.substringAfterLast('.', "").lowercase()
                     if (ext in imageExtensions || ext in videoExtensions) {
                         // Check size/existence logic
                         val remoteSize = file.additional?.size ?: 0L
                         // Compute local path (simplified check here, detailed in loop)
                         // We just filter based on type for now, existence check is done inside or here.
                         // Let's reuse existing existence logic if possible, or move it here.
                         // To keep it simple and safe, we just check extension here for splitting.
                         // But we must also check existence to filter out ALREADY BACKED UP files from filesToDownload.
                         // Leftovers are simply those that are NOT media.
                         // Be careful: if a file is media but already backed up, it is NOT a leftover.
                         filesToDownload.add(file)
                     } else {
                         nonMediaFiles.add(file)
                     }
                }
                
                // Filter filesToDownload for existence/size limits
                val realFilesToDownload = mutableListOf<FileInfo>()
                 for (file in filesToDownload) {
                        if (plannedDownloadBytes >= maxBytes) break
                        val remoteSize = file.additional?.size ?: 0L
                        // Compute local subdirectory mirroring NAS folder structure
                        val parentPath = file.path.substringBeforeLast('/')
                        val localSubDir = if (parentPath.length > "/photo".length) {
                            "nas" + parentPath.substring("/photo".length)
                        } else {
                            "nas"
                        }
                        val localDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), localSubDir)
                        val targetFile = File(localDir, file.name)
                        if (targetFile.exists() && targetFile.length() == remoteSize) continue
                        
                        realFilesToDownload.add(file)
                        plannedDownloadBytes += remoteSize
                    }
                    
                    withContext(Dispatchers.Main) {
                        debugLog += "Backup Plan: ${realFilesToDownload.size} files, approx ${plannedDownloadBytes / 1024} KB\n"
                        appendLog(context, "Backup Plan: ${realFilesToDownload.size} files, approx ${plannedDownloadBytes / 1024} KB")
                        if (nonMediaFiles.isNotEmpty()) {
                            leftoverFiles = nonMediaFiles
                            showLeftoverDialog = true
                        }
                    }
                    
                    var currentDownloadedBytes = 0L
                    var filesDone = 0
                    val backupStartTime = System.currentTimeMillis()
                    var lastNotifyTime = 0L
                    
                    for ((index, file) in realFilesToDownload.withIndex()) {
                        val remoteSize = file.additional?.size ?: 0L
                        val sizeStr = when {
                            remoteSize >= 1024 * 1024 -> String.format("%.2f MB", remoteSize / (1024.0 * 1024.0))
                            remoteSize >= 1024 -> String.format("%.2f KB", remoteSize / 1024.0)
                            else -> "$remoteSize B"
                        }
                        
                        if (!isBackupRunning) break // Allow stopping current job
                        appendLog(context, "Start processing: ${file.name} ($sizeStr)")
                        if (currentDownloadedBytes >= maxBytes) break
                        
                        try {
                            withContext(Dispatchers.Main) {
                                val currentMB = currentDownloadedBytes / (1024.0 * 1024.0)
                                val totalMB = plannedDownloadBytes / (1024.0 * 1024.0)
                                
                                // ETA Calculation
                                val elapsedTime = System.currentTimeMillis() - backupStartTime
                                var etaStr = ""
                                if (currentDownloadedBytes > 0 && elapsedTime > 2000) { // Wait 2s for stability
                                    val bytesPerSec = (currentDownloadedBytes.toDouble() * 1000) / elapsedTime
                                    val remainingBytes = plannedDownloadBytes - currentDownloadedBytes
                                    if (bytesPerSec > 0) {
                                        val remainingSeconds = (remainingBytes / bytesPerSec).toLong()
                                        etaStr = if (remainingSeconds < 60) " ETA: ${remainingSeconds}s" 
                                                 else if (remainingSeconds < 3600) " ETA: ${remainingSeconds/60}m ${remainingSeconds%60}s"
                                                 else " ETA: ${remainingSeconds/3600}h ${(remainingSeconds%3600)/60}m"
                                    }
                                }

                                // Update Notification (throttled to 1s)
                                if (System.currentTimeMillis() - lastNotifyTime > 1000) {
                                    val progress = ((index + 1) * 100) / realFilesToDownload.size
                                    val cleanEta = etaStr.replace(" ETA:", "")
                                    
                                    notificationBuilder.setProgress(100, progress, false)
                                        .setContentTitle("Backup $progress% (${index + 1}/${realFilesToDownload.size})")
                                        .setContentText("${file.name} ($sizeStr)")
                                        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                                            .bigText("File: ${file.name}\nSize: $sizeStr\nTime Left: $cleanEta"))
                                    
                                    if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        notificationManager.notify(notificationId, notificationBuilder.build())
                                    }
                                    lastNotifyTime = System.currentTimeMillis()
                                }

                                val progressStr = if (totalMB > 921.6) { // 0.9 GB * 1024 MB/GB = 921.6 MB
                                    String.format("%.2f/%.2f GB", currentMB / 1024.0, totalMB / 1024.0)
                                } else {
                                    String.format("%.1f/%.1f MB", currentMB, totalMB)
                                }
                                statusMessage = "Downloading ${index + 1}/${realFilesToDownload.size} ($progressStr$etaStr): ${file.name} ($sizeStr)"
                                debugLog += "Start: ${file.name} ($sizeStr)\n"
                            }
                            
                            val stream = repository.downloadFile(file.path)
                            if (stream != null) {
                                val context2 = context
                                // Compute local subDir mirroring NAS folder tree
                                val fileParentPath = file.path.substringBeforeLast('/')
                                val localSubDir = if (fileParentPath.length > "/photo".length) {
                                    "nas" + fileParentPath.substring("/photo".length)
                                } else {
                                    "nas"
                                }
                                val success = saveToMediaStore(context2, stream, file.name, localSubDir, remoteSize)
                                
                                if (success) {
                                    currentDownloadedBytes += remoteSize
                                    filesDone++
                                    withContext(Dispatchers.Main) {
                                        debugLog += "Downloaded: ${file.name}. Moving on NAS...\n"
                                        appendLog(context, "Downloaded: ${file.name}. Moving on NAS...")
                                    }
                                    
                                    var destFolder = "/homes/$username/google_photo_backup"
                                    if (file.path.startsWith("/photo/")) {
                                        val parentPath = file.path.substringBeforeLast('/')
                                        if (parentPath.length > "/photo".length) {
                                            val subPath = parentPath.substring("/photo".length)
                                            destFolder += subPath
                                        }
                                    }
                                    
                                    repository.createFolder(destFolder)
                                    val moveSuccess = repository.moveFile(file.path, destFolder)
                                    
                                    withContext(Dispatchers.Main) {
                                        if (moveSuccess) {
                                            debugLog += " - Move to $destFolder: Success\n"
                                            appendLog(context, " - Move to $destFolder: Success")
                                        } else {
                                            debugLog += " - Move: Failed\n"
                                            appendLog(context, " - Move: Failed")
                                        }
                                    }
                                    
                                    if (moveSuccess) {
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
                                            withContext(Dispatchers.Main) {
                                                if (delResult == "Success") {
                                                    debugLog += " - Auto-Fix: Source deleted\n"
                                                    appendLog(context, " - Auto-Fix: Source deleted")
                                                } else {
                                                    debugLog += " - Auto-Fix Failed: $delResult\n"
                                                    appendLog(context, " - Auto-Fix Failed: $delResult")
                                                }
                                            }
                                        }
                                    }
                                    

                                } else {
                                    withContext(Dispatchers.Main) {
                                        debugLog += "Failed to save: ${file.name}\n"
                                        appendLog(context, "Failed to save: ${file.name}")
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    debugLog += "Failed to open stream for ${file.name}\n"
                                    appendLog(context, "Failed to open stream for ${file.name}")
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                debugLog += "Error: ${file.name}: ${e.message}\n"
                                appendLog(context, "Error: ${file.name}: ${e.message}")
                            }
                        }
                    }
                    
                    // Clean up empty directories for recursive backups
                    if (job is BackupJob.RecursiveBackup) {
                        withContext(Dispatchers.Main) {
                            debugLog += "Checking for empty directories...\n"
                            appendLog(context, "Checking for empty directories...")
                        }
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
                                if (result == "Success") {
                                    deletedDirs++
                                    withContext(Dispatchers.Main) {
                                        debugLog += "Deleted empty dir: $dir\n"
                                        appendLog(context, "Deleted empty dir: $dir")
                                    }
                                }
                            }
                        }
                        if (deletedDirs > 0) {
                            withContext(Dispatchers.Main) {
                                debugLog += "Cleaned up $deletedDirs empty directories\n"
                                appendLog(context, "Cleaned up $deletedDirs empty directories")
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        val totalMB = currentDownloadedBytes / (1024 * 1024)
                        statusMessage = "Done: $filesDone files ($totalMB MB). Queue: ${backupQueue.size} remaining"
                        debugLog += "=== Job complete: ${job.label} ===\n"
                        appendLog(context, "=== Job complete: ${job.label} ===")
                        refreshList(sourcePath)
                    }
                }
            } catch (e: Exception) {
                debugLog += "Queue job error: ${e.message}\n"
                appendLog(context, "Queue job error: ${e.message}")
            } finally {
                notificationManager.cancel(notificationId)
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                    appendLog(context, "WakeLock released")
                }
                isBackupRunning = false
                currentBackupLabel = ""
            }
            }
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        if (!isLoggedIn) {
            // === STEP 1: NAS Address ===
            if (loginStep == 1) {
                Text("NAS Address", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host URL (e.g. https://192.168.0.x:5001)") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Find NAS button
                Button(onClick = {
                    scope.launch {
                        isScanning = true
                        discoveredNas = emptyList()
                        statusMessage = "Scanning network..."
                        appendLog(context, "Scanning network for NAS...")
                        withContext(Dispatchers.IO) {
                            try {
                                // Get device IP
                                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                                val ipInt = wifiManager.connectionInfo.ipAddress
                                val ip = String.format(
                                    "%d.%d.%d.%d",
                                    ipInt and 0xff, (ipInt shr 8) and 0xff,
                                    (ipInt shr 16) and 0xff, (ipInt shr 24) and 0xff
                                )
                                val subnet = ip.substringBeforeLast('.')
                                
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Scanning $subnet.1-254 on port 5001..."
                                    appendLog(context, "Scanning $subnet.1-254 on port 5001...")
                                }
                                
                                val found = repository.scanSubnet(subnet, 5001)
                                withContext(Dispatchers.Main) {
                                    discoveredNas = found
                                    statusMessage = if (found.isEmpty()) "No NAS found" else "Found ${found.size} device(s)"
                                    appendLog(context, if (found.isEmpty()) "No NAS found" else "Found ${found.size} device(s)")
                                    isScanning = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Scan error: ${e.message}"
                                    debugLog += "Scan error: ${e.message}\n"
                                    appendLog(context, "Scan error: ${e.message}")
                                    isScanning = false
                                }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                   enabled = !isScanning) {
                    Text(if (isScanning) "Scanning..." else "Find NAS Address")
                }
                
                // Display discovered NAS addresses
                if (discoveredNas.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Found NAS devices:", style = MaterialTheme.typography.labelMedium)
                    discoveredNas.forEach { nasIp ->
                        TextButton(onClick = { host = "https://$nasIp:5001" }) {
                            Text("https://$nasIp:5001")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (host.isNotBlank()) {
                        loginStep = 2
                    }
                }, modifier = Modifier.fillMaxWidth(),
                   enabled = host.isNotBlank()) {
                    Text("Next")
                }
            }
            
            // === STEP 2: Username ===
            else if (loginStep == 2) {
                Text("Username", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("NAS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(host, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                
                TextButton(onClick = { loginStep = 1 }) {
                    Text("Change NAS Address")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (username.isNotBlank()) {
                        loginStep = 3
                    }
                }, modifier = Modifier.fillMaxWidth(),
                   enabled = username.isNotBlank()) {
                    Text("Next")
                }
            }
            
            // === STEP 3: Password ===
            else if (loginStep == 3) {
                Text("Login to NAS", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Show saved host/user
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("NAS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(host, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("User", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        Text(username, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                
                TextButton(onClick = { loginStep = 1 }) {
                    Text("Change NAS / User")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, 
                    onValueChange = { password = it }, 
                    label = { Text("Password") }, 
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        statusMessage = "Logging in..."
                        debugLog += "Attempting login to $host with user $username...\n"
                        appendLog(context, "Attempting login to $host with user $username...")
                        withContext(Dispatchers.IO) {
                            try {
                                repository.init(host)
                                val result = repository.login(
                                    account = username, 
                                    passwd = password,
                                    deviceName = "SynologyDownloaderAndroid",
                                    deviceId = deviceId
                                )
                                withContext(Dispatchers.Main) {
                                    debugLog += "Result: ${result.message}\n"
                                    appendLog(context, "Login Result: ${result.message}")
                                    if (result.message == "Success") {
                                        isLoggedIn = true
                                        statusMessage = "Logged in"
                                        
                                        // Save device ID if returned
                                        if (result.did != null) {
                                            deviceId = result.did
                                            sharedPref.edit()
                                                .putString("device_id", result.did)
                                                .apply()
                                        }
                                        
                                        sharedPref.edit()
                                            .putString("last_user", username)
                                            .putString("last_host", host)
                                            .apply()
                                        
                                        refreshList(sourcePath)
                                    } else if (result.needsOtp) {
                                        statusMessage = "OTP Required"
                                        loginStep = 4
                                    } else {
                                        statusMessage = "Login failed"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    val msg = "Exception: ${e.message}"
                                    statusMessage = "Error occured"
                                    debugLog += "$msg\n"
                                }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Login")
                }
            }
            
            // === STEP 4: OTP ===
            else if (loginStep == 4) {
                Text("Verification Code", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("NAS: $host", style = MaterialTheme.typography.bodySmall)
                        Text("User: $username", style = MaterialTheme.typography.bodySmall)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("OTP Code (6 digits)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberDevice,
                        onCheckedChange = { rememberDevice = it }
                    )
                    Text("Don't ask again on this device")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        statusMessage = "Verifying OTP..."
                        withContext(Dispatchers.IO) {
                            try {
                                repository.init(host)
                                val result = repository.login(
                                    account = username,
                                    passwd = password,
                                    otpCode = otpCode,
                                    enableDeviceToken = rememberDevice,
                                    deviceName = "SynologyDownloaderAndroid"
                                )
                                withContext(Dispatchers.Main) {
                                    debugLog += "OTP Result: ${result.message}\n"
                                    if (result.message == "Success") {
                                        isLoggedIn = true
                                        statusMessage = "Logged in"
                                        
                                        // Save device ID for future OTP skip
                                        if (result.did != null) {
                                            deviceId = result.did
                                            sharedPref.edit()
                                                .putString("device_id", result.did)
                                                .apply()
                                        }
                                        
                                        sharedPref.edit()
                                            .putString("last_user", username)
                                            .putString("last_host", host)
                                            .apply()
                                        
                                        refreshList(sourcePath)
                                    } else {
                                        statusMessage = "OTP Failed: ${result.message}"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Error: ${e.message}"
                                    debugLog += "OTP Exception: ${e.message}\n"
                                }
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Verify")
                }
                
                TextButton(onClick = { loginStep = 3 }) {
                    Text("Back to Password")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = statusMessage)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Debug Log UI
            var showDebugLog by remember { mutableStateOf(false) }
            Button(onClick = { showDebugLog = !showDebugLog }, modifier = Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                Text(if (showDebugLog) "Hide Debug Log" else "Show Debug Log")
            }
            
            if (showDebugLog) {
                val scrollState = rememberScrollState()
                
                LaunchedEffect(Unit) {
                    delay(100)
                    scrollState.scrollTo(Int.MAX_VALUE)
                }
                
                LaunchedEffect(scrollState.maxValue) {
                    if (scrollState.maxValue > 0) {
                         val dist = scrollState.maxValue - scrollState.value
                         if (dist < 200) {
                              scrollState.scrollTo(scrollState.maxValue)
                         }
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.inverseOnSurface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        .verticalScroll(scrollState)
                        .padding(8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = debugLog,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

        } else {
            OutlinedTextField(
                value = TextFieldValue(sourcePath, TextRange(sourcePath.length)),
                onValueChange = { sourcePath = it.text },
                label = { Text("Source Path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<FileInfo>()) }

    // ... (rest of the code)

            if (isSelectionMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        isSelectionMode = false
                        selectedFiles = emptySet()
                    }) {
                        Text("Cancel")
                    }
                    
                    Button(onClick = {
                        scope.launch {
                            statusMessage = "Deleting selected files..."
                            debugLog = ""
                            val filesToDelete = selectedFiles.toList()
                            var successCount = 0
                            
                            withContext(Dispatchers.IO) {
                                for (file in filesToDelete) {
                                    withContext(Dispatchers.Main) { debugLog += "Deleting ${file.name}...\n" }
                                    val result = repository.deleteFile(file.path)
                                    withContext(Dispatchers.Main) {
                                        if (result == "Success") {
                                            debugLog += " - Success\n"
                                            successCount++
                                        } else {
                                            debugLog += " - Failed: $result\n"
                                        }
                                    }
                                }
                            }
                            
                            isSelectionMode = false
                            selectedFiles = emptySet()
                            statusMessage = "Deleted $successCount files"
                            withContext(Dispatchers.Main) {
                                refreshList(sourcePath)
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.weight(1f)) {
                        Text("Del (${selectedFiles.size})")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        scope.launch {
                             statusMessage = "Downloading selected files..."
                            debugLog = ""
                            
                            val filesToDownload = selectedFiles.filter { !it.isdir }.toList()
                            var count = 0
                            var totalBytes = 0L
                            
                            withContext(Dispatchers.IO) {
                                for (file in filesToDownload) {
                                    try {
                                        val remoteSize = file.additional?.size ?: 0L
                                        
                                        withContext(Dispatchers.Main) {
                                            debugLog += "Processing ${file.name}...\n"
                                        }

                                        // Check if exists
                                        val existingSize = findFileInMediaStore(context, file.name, "nas")
                                        
                                        if (existingSize != null) {
                                            if (existingSize == remoteSize) {
                                                withContext(Dispatchers.Main) {
                                                    debugLog += " - Skipped (Identical file exists)\n"
                                                }
                                                continue
                                            }
                                            withContext(Dispatchers.Main) {
                                                 debugLog += " - File exists (size diff), saving new version...\n"
                                            }
                                            // MediaStore will auto-rename (e.g. file(1).txt) if we insert again.
                                            // Or we could handle renaming manually if strict about "backup" names.
                                            // For "Download Selected", standard system behavior (auto-rename) is usually preferred.
                                        }

                                        withContext(Dispatchers.Main) {
                                            statusMessage = "Downloading ${file.name}..."
                                        }

                                        val stream = repository.downloadFile(file.path)
                                        if (stream != null) {
                                            val success = saveToMediaStore(context, stream, file.name, "nas", remoteSize)
                                            if (success) {
                                                totalBytes += remoteSize
                                                count++
                                                withContext(Dispatchers.Main) {
                                                    debugLog += " - Download Success\n"
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    debugLog += " - Failed to save locally\n"
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                debugLog += " - Failed: InputStream was null\n"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                           debugLog += " - Error: ${e.message}\n"
                                           e.printStackTrace()
                                        }
                                    }
                                }
                            }
                            isSelectionMode = false
                            selectedFiles = emptySet()
                            val totalMB = totalBytes / (1024 * 1024)
                            statusMessage = "Download complete: $count files ($totalMB MB)"
                            
                            withContext(Dispatchers.Main) {
                                debugLog += "Refreshing file list...\n"
                                refreshList(sourcePath)
                            }
                        }
                    }) {
                        Text("Download (${selectedFiles.size})")
                    }
                    
                    Button(onClick = {
                        val files = selectedFiles.filter { !it.isdir }.toList()
                        val newJob = BackupJob.SelectedBackup(files, sourcePath)
                        
                        if (isBackupRunning && currentBackupLabel == newJob.label) {
                            Toast.makeText(context, "Task is currently running!", Toast.LENGTH_SHORT).show()
                        } else if (backupQueue.any { it.label == newJob.label }) {
                             Toast.makeText(context, "Task is already in queue!", Toast.LENGTH_SHORT).show()
                        } else {
                            backupQueue = backupQueue + newJob
                            statusMessage = "Queued: ${files.size} files (Queue: ${backupQueue.size})"
                            isSelectionMode = false
                            selectedFiles = emptySet()
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("Backup (${selectedFiles.size})")
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = {
                    val newJob = BackupJob.RecursiveBackup(sourcePath)
                    
                    if (isBackupRunning && currentBackupLabel == newJob.label) {
                        Toast.makeText(context, "Task is currently running!", Toast.LENGTH_SHORT).show()
                    } else if (backupQueue.any { it.label == newJob.label }) {
                         Toast.makeText(context, "Task is already in queue!", Toast.LENGTH_SHORT).show()
                    } else {
                        backupQueue = backupQueue + newJob
                        statusMessage = "Queued: Full Backup of $sourcePath (Queue: ${backupQueue.size})"
                    }
                }) {
                    Text("Start Backup")
                }



                    Button(onClick = {
                        scope.launch {
                            statusMessage = "Downloading all..."
                            var count = 0
                            withContext(Dispatchers.IO) {
                                 val filesToDownload = fileList.filter { !it.isdir }
                                 filesToDownload.forEach { file ->
                                    val stream = repository.downloadFile(file.path)
                                    if (stream != null) {
                                        saveToMediaStore(context, stream, file.name, "SynologyDownloader", 0)
                                        count++
                                        withContext(Dispatchers.Main) {
                                                statusMessage = "Downloaded $count files..."
                                        }
                                    }
                                }
                            }
                            statusMessage = "Download completed ($count files)"
                        }
                    }) {
                        Text("Download All")
                    }
                }

                // Compact Queue Status UI (Expandable)
                if (isBackupRunning || backupQueue.isNotEmpty()) {
                    var isQueueExpanded by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { isQueueExpanded = !isQueueExpanded },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // 1. Running Status
                            if (isBackupRunning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text("Running:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = currentBackupLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = if (isQueueExpanded) Int.MAX_VALUE else 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    Button(
                                        onClick = { 
                                            activeBackupJob?.cancel()
                                            backupQueue = emptyList()
                                            statusMessage = "Cancelled"
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Stop")
                                    }
                                }
                            }

                            // 2. Queue Status
                            if (backupQueue.isNotEmpty()) {
                                if (isBackupRunning) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Pending: ${backupQueue.size} jobs" + if(isQueueExpanded) "" else " (Tap for details)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    
                                    if (!isQueueExpanded) {
                                         Button(
                                            onClick = { backupQueue = emptyList() },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("Clear", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            
                            // 3. Expanded List
                            if (isQueueExpanded && backupQueue.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                backupQueue.forEachIndexed { index, job ->
                                    Text(
                                        "${index + 1}. ${job.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { backupQueue = emptyList() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Clear All Queue")
                                }
                            }
                        }
                    }
                }
            }


            LazyColumn(modifier = Modifier.weight(1f)) {
                if (sourcePath != "/" && sourcePath != "/photo" && sourcePath.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val parentPath = if (sourcePath.lastIndexOf('/') > 0) {
                                        sourcePath.substring(0, sourcePath.lastIndexOf('/'))
                                    } else {
                                        "/"
                                    }
                                    sourcePath = parentPath
                                    refreshList(parentPath)
                                }
                                .padding(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Parent Folder",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = ".. (Parent Folder)", style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider()
                    }
                }
                 
                 items(fileList) { file ->
                    val isSelected = selectedFiles.contains(file)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        val newSelection = selectedFiles.toMutableSet()
                                        if (isSelected) newSelection.remove(file) else newSelection.add(file)
                                        selectedFiles = newSelection
                                    } else {
                                        if (file.isdir) {
                                            sourcePath = file.path
                                            refreshList(file.path)
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedFiles = setOf(file)
                                    }
                                }
                            )
                            .padding(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (isSelectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    val newSelection = selectedFiles.toMutableSet()
                                    if (checked) newSelection.add(file) else newSelection.remove(file)
                                    selectedFiles = newSelection
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        
                        Icon(
                            imageVector = if (file.isdir) Icons.Default.Folder else Icons.Default.Info,
                            contentDescription = if (file.isdir) "Folder" else "File",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = file.name)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            
            // Debug Log UI with Auto-Scroll
            if (isLoggedIn || debugLog.isNotEmpty()) {
                var showDebugLog by remember { mutableStateOf(false) }
                
                Button(onClick = { showDebugLog = !showDebugLog }, modifier = Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                    Text(if (showDebugLog) "Hide Debug Log" else "Show Debug Log")
                }
                
                if (showDebugLog) {
                    val scrollState = rememberScrollState()
                    
                    // Initial scroll to bottom
                    LaunchedEffect(Unit) {
                        delay(100) // Wait for initial layout
                        scrollState.scrollTo(Int.MAX_VALUE)
                    }
                    
                    // Smart auto-scroll: Only scroll if user is already at the bottom
                    LaunchedEffect(scrollState.maxValue) {
                        if (scrollState.maxValue > 0) {
                             val dist = scrollState.maxValue - scrollState.value
                             // Threshold: 200px. If close to bottom, update to new bottom.
                             if (dist < 200) {
                                  scrollState.scrollTo(scrollState.maxValue)
                             }
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.inverseOnSurface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .verticalScroll(scrollState)
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = debugLog,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            
            if (showStorageErrorDialog) {
                AlertDialog(
                    onDismissRequest = { showStorageErrorDialog = false },
                    title = { Text("Storage Warning") },
                    text = { Text("Backup cannot proceed because the device has less than 10GB of free space.\n\nPlease free up space and try again.") },
                    confirmButton = {
                        Button(onClick = { showStorageErrorDialog = false }) {
                            Text("OK")
                        }
                    },
                    icon = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                )
            }
            
            if (showLeftoverDialog) {
                AlertDialog(
                    onDismissRequest = { showLeftoverDialog = false },
                    title = { Text("Non-Media Files Found") },
                    text = {
                        Column {
                            val summary = remember(leftoverFiles) {
                                leftoverFiles.groupingBy { 
                                    val ext = it.name.substringAfterLast('.', "")
                                    if(ext.isEmpty()) "No Ext" else ".${ext.lowercase()}"
                                }.eachCount().entries.sortedByDescending { it.value }
                                .joinToString(", ") { "${it.key}: ${it.value}" }
                            }
                            
                            Text("Found ${leftoverFiles.size} skipped files.")
                            Text("Types: $summary", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("The list below shows files found in folders we backed up. You can choose to delete them from the NAS.")
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            HorizontalDivider()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            ) {
                                items(leftoverFiles) { file ->
                                    Text(
                                        file.name,
                                        style = MaterialTheme.typography.bodySmall, 
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                            HorizontalDivider()
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showLeftoverDialog = false
                                scope.launch {
                                    statusMessage = "Deleting leftovers..."
                                    var delCount = 0
                                    var deletedDirs = 0
                                    val parentDirsToCheck = mutableSetOf<String>()
                                    
                                    withContext(Dispatchers.IO) {
                                        // 1. Delete Files
                                        leftoverFiles.forEach { file ->
                                            if (repository.deleteFile(file.path) == "Success") {
                                                delCount++
                                                val parent = file.path.substringBeforeLast('/')
                                                if (parent.isNotEmpty() && parent != "/") {
                                                    parentDirsToCheck.add(parent)
                                                }
                                            }
                                        }
                                        
                                        // 2. Check and Delete Empty Directories
                                        parentDirsToCheck.forEach { dir ->
                                            val contents = repository.listFiles(dir)
                                            if (contents.isEmpty()) {
                                                if (repository.deleteFile(dir) == "Success") {
                                                    deletedDirs++
                                                }
                                            }
                                        }
                                    }
                                    statusMessage = "Deleted $delCount files and $deletedDirs empty folders."
                                    refreshList(sourcePath)
                                }
                            },
                             colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete All") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLeftoverDialog = false }) { Text("Keep") }
                    }
                )
            }
        }
    }
}

/**
 * Checks if a file exists in the Downloads/subDir folder using MediaStore.
 * Returns the size of the file if found, or null if not found.
 */
fun findFileInMediaStore(context: Context, fileName: String, subDir: String): Long? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(fileName, "%$subDir%") // Loose match for subdir
        
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                return cursor.getLong(sizeIndex)
            }
        }
    } else {
        // Legacy file check
        val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
        val targetFile = File(targetDir, fileName)
        if (targetFile.exists()) return targetFile.length()
    }
    return null
}

suspend fun saveToMediaStore(context: Context, inputStream: InputStream, fileName: String, subDir: String, expectedSize: Long): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Check if file exists to handle duplicates/renaming logic if needed?
                // For 'save', MediaStore usually auto-renames duplicates (e.g. file(1).jpg).
                // But for our Backup 'sync' logic, we want to know if we should skip.
                // The caller should have called findFileInMediaStore first.
                // Here we just write.

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream") // Or detect mime type
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$subDir")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                    true
                } ?: false
            } else {
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                
                // Legacy simple rename logic
                var finalFile = targetFile
                var v = 1
                val name = fileName.substringBeforeLast('.')
                val ext = fileName.substringAfterLast('.', "")
                val dot = if (ext.isNotEmpty()) "." else ""
                
                while (finalFile.exists()) {
                   finalFile = File(targetDir, "${name}_$v$dot$ext")
                   v++
                }
                
                FileOutputStream(finalFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

fun appendLog(context: Context, msg: String) {
    try {
        val file = File(context.filesDir, "last_session_log.txt")
        // Rotate log if too big (500KB)
        if (file.exists() && file.length() > 500 * 1024) {
            file.delete()
        }
        java.io.FileOutputStream(file, true).use {
            it.write(("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} : $msg\n").toByteArray())
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun readLastLog(context: Context): String {
    return try {
        val file = File(context.filesDir, "last_session_log.txt")
        if (file.exists()) {
            // Read last 5KB to avoid huge memory usage
            val length = file.length()
            val toRead = if (length > 5000) 5000 else length.toInt()
            val bytes = ByteArray(toRead)
            
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - toRead)
                raf.readFully(bytes)
            }
            String(bytes, Charsets.UTF_8)
        } else ""
    } catch (e: Exception) {
        "Error reading log: ${e.message}\n"
    } 
}