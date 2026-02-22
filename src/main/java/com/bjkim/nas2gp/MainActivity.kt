package com.bjkim.nas2gp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import com.bjkim.nas2gp.network.FileInfo
import com.bjkim.nas2gp.network.AdditionalInfo
import com.bjkim.nas2gp.repository.SynologyRepository
import com.bjkim.nas2gp.ui.theme.TestTheme
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
import com.bjkim.nas2gp.auth.GoogleSignInHelper
import com.bjkim.nas2gp.service.GooglePhotosUploadManager
import com.bjkim.nas2gp.service.GooglePhotosUploadService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.content.Intent
import com.bjkim.nas2gp.model.BackupJob
import com.bjkim.nas2gp.service.BackupManager
import com.bjkim.nas2gp.service.BackupService
import com.bjkim.nas2gp.utils.appendLog
import com.bjkim.nas2gp.utils.readLastLog
import com.bjkim.nas2gp.utils.findFileInMediaStore
import com.bjkim.nas2gp.utils.saveToMediaStore

fun logToUiAndFile(context: Context, msg: String) {
    android.util.Log.d("SynologyDownloader", msg)
    BackupManager.appendLog(msg)
    appendLog(context, msg)
}




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

    var fileList by remember { mutableStateOf<List<FileInfo>>(emptyList()) }

    // Launcher for Android 10 storage permissions
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (readGranted) {
            logToUiAndFile(context, "Storage permissions granted! You can now start the upload.")
        } else {
            logToUiAndFile(context, "Storage permission was denied.")
        }
    }

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
    
    // Backup queue (Managed by BackupManager)
    val backupQueue by com.bjkim.nas2gp.service.BackupManager.queue.collectAsState()
    val isBackupRunning by com.bjkim.nas2gp.service.BackupManager.isBackupRunning.collectAsState()
    // Local status for non-backup ops, or shared?
    // Let's keep local statusMessage for Login/Listing, but observe BackupManager for updates?
    // Actually, let's mix them.
    var localStatusMessage by remember { mutableStateOf("Ready") }
    val serviceStatusMessage by com.bjkim.nas2gp.service.BackupManager.statusMessage.collectAsState()
    
    // Derived status: if backup running, show service status, else local
    val statusMessage = if (isBackupRunning) serviceStatusMessage else localStatusMessage
    
    // Debug Log: Service log + Local log?
    // Let's use BackupManager.debugLog as the source of truth if possible, or just append local logs to it.
    val debugLog by com.bjkim.nas2gp.service.BackupManager.debugLog.collectAsState()
    
    val activeJob by com.bjkim.nas2gp.service.BackupManager.activeJob.collectAsState()
    val currentBackupLabel = activeJob?.label ?: "" 
    // Actually BackupManager doesn't expose current label directly except via status?
    // We can filter queue to guess.
    // Or add currentJobLabel to BackupManager.
    
    var activeBackupJob by remember { mutableStateOf<Job?>(null) }
    
    LaunchedEffect(Unit) {
        com.bjkim.nas2gp.service.BackupManager.repository = repository
    }
    
    // Google Photos State
    var googleAccount by remember { mutableStateOf<GoogleSignInAccount?>(null) }
    var googleSignInError by remember { mutableStateOf<String?>(null) }
    val isGoogleUploading by com.bjkim.nas2gp.service.GooglePhotosUploadManager.isUploading.collectAsState()
    val googleUploadProgress by com.bjkim.nas2gp.service.GooglePhotosUploadManager.progress.collectAsState()
    val googleUploadStatus by com.bjkim.nas2gp.service.GooglePhotosUploadManager.statusMessage.collectAsState()
    val googleTotalBytesTarget by com.bjkim.nas2gp.service.GooglePhotosUploadManager.totalBytesTarget.collectAsState()
    val googleTotalBytesUploaded by com.bjkim.nas2gp.service.GooglePhotosUploadManager.totalBytesUploaded.collectAsState()
    val googleCurrentFileBytesUploaded by com.bjkim.nas2gp.service.GooglePhotosUploadManager.currentFileBytesUploaded.collectAsState()
    val googleCurrentFileTotalBytes by com.bjkim.nas2gp.service.GooglePhotosUploadManager.currentFileTotalBytes.collectAsState()
    val googleEtaString by com.bjkim.nas2gp.service.GooglePhotosUploadManager.etaString.collectAsState()
    
    val splitThumbnails by BackupManager.splitThumbnails.collectAsState()
    
    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        logToUiAndFile(context, "Google Sign-In result code: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                googleAccount = account
                logToUiAndFile(context, "Google Sign-In Success: ${account.email}")
            } catch (e: ApiException) {
                val errorMsg = "Google Sign-In failed: ${e.statusCode} ${e.message}"
                logToUiAndFile(context, errorMsg)
                googleSignInError = errorMsg
            }
        } else {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                task.getResult(ApiException::class.java) // Just to trigger the exception extraction
            } catch(e: ApiException) {
                val errorMsg = "Google Sign-In canceled/error (code ${result.resultCode}): ${e.statusCode} ${e.message}"
                logToUiAndFile(context, errorMsg)
                googleSignInError = errorMsg
            }
        }
    }

    LaunchedEffect(Unit) {
        googleAccount = GoogleSignIn.getLastSignedInAccount(context)
    }

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
        val lastLog: String = withContext(Dispatchers.IO) { readLastLog(context) }
        if (lastLog.isNotEmpty()) {
            BackupManager.debugLog.value = "--- LAST SESSION LOG (Potential Crash Context) ---\n$lastLog\n--------------------------\n"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                 try {
                     val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                     intent.data = android.net.Uri.parse("package:${context.packageName}")
                     context.startActivity(intent)
                 } catch (e: Exception) {
                     e.printStackTrace()
                 }
            }
        }

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
            localStatusMessage = "Listing files..."
            withContext(Dispatchers.IO) {
                try {
                    val files = repository.listFiles(path).filter { 
                        !it.name.equals("folder.jpg", ignoreCase = true) &&
                        !it.name.endsWith("-poster.jpg", ignoreCase = true)
                    }
                    withContext(Dispatchers.Main) {
                        fileList = files
                        localStatusMessage = "Files listed: ${files.size}"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        localStatusMessage = "Error listing files: ${e.message}"
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

    // Queue processor removed (handled by Service)


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
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onNext = { if (host.isNotBlank()) loginStep = 2 }
                    )
                )
                
                // Find NAS button
                Button(onClick = {
                    scope.launch {
                        isScanning = true
                        discoveredNas = emptyList()
                        localStatusMessage = "Scanning network..."
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
                                    localStatusMessage = "Scanning $subnet.1-254 on port 5001..."
                                    appendLog(context, "Scanning $subnet.1-254 on port 5001...")
                                }
                                
                                val found = repository.scanSubnet(subnet, 5001)
                                withContext(Dispatchers.Main) {
                                    discoveredNas = found
                                    localStatusMessage = if (found.isEmpty()) "No NAS found" else "Found ${found.size} device(s)"
                                    appendLog(context, if (found.isEmpty()) "No NAS found" else "Found ${found.size} device(s)")
                                    isScanning = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    localStatusMessage = "Scan error: ${e.message}"
                                    logToUiAndFile(context, "Scan error: ${e.message}")
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
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onNext = { if (username.isNotBlank()) loginStep = 3 }
                    )
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
                val performLogin = {
                    scope.launch {
                        localStatusMessage = "Logging in..."
                        logToUiAndFile(context, "Attempting login to $host with user $username...")
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
                                    logToUiAndFile(context, "Login Result: ${result.message}")
                                    if (result.message == "Success") {
                                        isLoggedIn = true
                                        localStatusMessage = "Logged in"
                                        BackupManager.username = username
                                        BackupManager.host = host
                                        
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
                                        localStatusMessage = "OTP Required"
                                        loginStep = 4
                                    } else {
                                        localStatusMessage = "Login failed"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    val msg = "Exception: ${e.message}"
                                    localStatusMessage = "Error occured"
                                    logToUiAndFile(context, msg)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = password, 
                    onValueChange = { password = it }, 
                    label = { Text("Password") }, 
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { performLogin() }
                    )
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { performLogin() }, modifier = Modifier.fillMaxWidth()) {
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
                val performOtp = {
                    scope.launch {
                        localStatusMessage = "Verifying OTP..."
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
                                    logToUiAndFile(context, "OTP Result: ${result.message}")
                                    if (result.message == "Success") {
                                        isLoggedIn = true
                                        localStatusMessage = "Logged in"
                                        BackupManager.username = username
                                        BackupManager.host = host
                                        
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
                                        localStatusMessage = "OTP Failed: ${result.message}"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    localStatusMessage = "Error: ${e.message}"
                                    logToUiAndFile(context, "OTP Exception: ${e.message}")
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it },
                    label = { Text("OTP Code (6 digits)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { performOtp() }
                    )
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
                Button(onClick = { performOtp() }, modifier = Modifier.fillMaxWidth()) {
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
            // Storage Usage Display
            val serviceStorageStats by BackupManager.storageStats.collectAsState()
            var localStorageStats by remember { mutableStateOf<Pair<Long, Long>?>(null) }
            
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val dir = Environment.getExternalStorageDirectory()
                    localStorageStats = Pair(dir.totalSpace, dir.freeSpace)
                    if (BackupManager.storageStats.value == null) {
                        BackupManager.storageStats.value = localStorageStats
                    }
                }
            }
            
            val storageStats = serviceStorageStats ?: localStorageStats
            
            storageStats?.let { (total, free) ->
                val used = total - free
                val usedGB = used / (1024.0 * 1024.0 * 1024.0)
                val totalGB = total / (1024.0 * 1024.0 * 1024.0)
                val percent = if (total > 0) (used.toDouble() / total.toDouble()) else 0.0
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Device Storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = String.format("%.2f GB / %.2f GB", usedGB, totalGB),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = percent.toFloat(),
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = if (percent > 0.9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            
            OutlinedTextField(
                value = TextFieldValue(sourcePath, TextRange(sourcePath.length)),
                onValueChange = { sourcePath = it.text },
                label = { Text("Source Path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { refreshList(sourcePath) }
                )
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
                            localStatusMessage = "Deleting selected files..."
                            BackupManager.debugLog.value = ""
                            val filesToDelete = selectedFiles.toList()
                            var successCount = 0
                            
                            withContext(Dispatchers.IO) {
                                for (file in filesToDelete) {
                                    withContext(Dispatchers.Main) { logToUiAndFile(context, "Deleting ${file.name}...") }
                                    val result = repository.deleteFile(file.path)
                                    withContext(Dispatchers.Main) {
                                        if (result == "Success") {
                                            logToUiAndFile(context, " - Success")
                                            successCount++
                                        } else {
                                            logToUiAndFile(context, " - Failed: $result")
                                        }
                                    }
                                }
                            }
                            
                            isSelectionMode = false
                            selectedFiles = emptySet()
                            localStatusMessage = "Deleted $successCount files"
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
                        val files = selectedFiles.filter { !it.isdir }.toList()
                        // Download Only (moveOnNas = false)
                        val newJob = BackupJob.SelectedBackup(files, sourcePath, moveOnNas = false)
                        
                         if (backupQueue.any { it.label == newJob.label }) {
                             Toast.makeText(context, "Task is already in queue!", Toast.LENGTH_SHORT).show()
                        } else {
                            BackupManager.addToQueue(newJob)
                            val intent = Intent(context, BackupService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            localStatusMessage = "Queued Download: ${files.size} files"
                            isSelectionMode = false
                            selectedFiles = emptySet()
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
                            BackupManager.addToQueue(newJob)
                            val intent = Intent(context, BackupService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            localStatusMessage = "Queued: ${files.size} files"
                            isSelectionMode = false
                            selectedFiles = emptySet()
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text("Backup (${selectedFiles.size})")
                    }
                }
            } else {




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
                                            // Stop Action
                                            val intent = Intent(context, BackupService::class.java)
                                            context.stopService(intent)
                                            BackupManager.clearQueue()
                                            localStatusMessage = "Cancelled"
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
                                            onClick = { 
                                                BackupManager.clearQueue()
                                            },
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
                                    onClick = { 
                                        BackupManager.clearQueue()
                                    },
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
            
            if (splitThumbnails.isNotEmpty()) {
                Text("Verification (Split Point Frames):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(splitThumbnails) { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Split Point",
                            modifier = Modifier
                                .height(80.dp)
                                .width(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            
            // Debug Log UI with Auto-Scroll
            if (isLoggedIn || debugLog.isNotEmpty()) {
                

                var showScanDialog by remember { mutableStateOf(false) }
                var scanResults by remember { mutableStateOf<List<List<String>>?>(null) }
                var isScanning by remember { mutableStateOf(false) }
                var isReDownloading by remember { mutableStateOf(false) }
                
                if (showScanDialog) {
                    var checkAllFiles by remember { mutableStateOf(false) }
                    var checkMetadata by remember { mutableStateOf(true) }
                    var checkDate by remember { mutableStateOf(false) }
                    var checkLargeFiles by remember { mutableStateOf(false) }
                    var dateString by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
                    
                    AlertDialog(
                        onDismissRequest = { if(!isScanning) showScanDialog = false },
                        title = { Text("Scan Local Files") },
                        text = {
                            Column {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = checkMetadata, onCheckedChange = { checkMetadata = it })
                                    Text("Missing Metadata (Images)")
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = checkDate, onCheckedChange = { checkDate = it })
                                    Text("Modified Date:")
                                }
                                if (checkDate) {
                                    OutlinedTextField(
                                        value = dateString,
                                        onValueChange = { dateString = it },
                                        label = { Text("YYYY-MM-DD") },
                                        singleLine = true
                                    )
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = checkAllFiles, onCheckedChange = { checkAllFiles = it })
                                    Text("List All Files")
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = checkLargeFiles, onCheckedChange = { checkLargeFiles = it })
                                    Text("Large Files (> 9.1GB)")
                                }
                                if (isScanning) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Scanning...", modifier = Modifier.padding(top=4.dp))
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    isScanning = true
                                    scope.launch(Dispatchers.IO) {
                                        val list = scanForIssues(
                                            onLog = { msg ->
                                                scope.launch(Dispatchers.Main) {
                                                    logToUiAndFile(context, msg)
                                                    appendLog(context, msg)
                                                }
                                            },
                                            checkMetadata,
                                            if(checkDate) dateString else null,
                                            checkAllFiles,
                                            checkLargeFiles
                                        )
                                        withContext(Dispatchers.Main) {
                                            scanResults = list
                                            isScanning = false
                                            showScanDialog = false
                                        }
                                    }
                                },
                                enabled = !isScanning && (checkMetadata || checkDate || checkAllFiles || checkLargeFiles)
                            ) { Text("Start Scan") }
                        },
                        dismissButton = {
                            if(!isScanning) Button(onClick = { showScanDialog = false }) { Text("Cancel") }
                        }
                    )
                }
                
                val results = scanResults
                if (results != null) {
                    // Selection state for scan results
                    val selectedScanItems = remember { mutableStateListOf<List<String>>() }
                    // Initialize with all selected or none? User usually wants to pick. Let's start with empty or logic? 
                    // Usually "checked by default" if issues found?
                    // Let's start with empty to be safe, or maybe none.
                    // Actually, let's start with NONE so user picks what to rename.
                    
                    AlertDialog(
                        onDismissRequest = { scanResults = null },
                        title = { 
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Scan Results (${results.size})", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (selectedScanItems.size == results.size) {
                                        selectedScanItems.clear()
                                    } else {
                                        selectedScanItems.clear()
                                        selectedScanItems.addAll(results)
                                    }
                                }) {
                                    Text(if (selectedScanItems.size == results.size) "Deselect All" else "Select All")
                                }
                            }
                        },
                        text = {
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                items(results) { item ->
                                    val isSelected = selectedScanItems.contains(item)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (isSelected) selectedScanItems.remove(item) else selectedScanItems.add(item)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                if (checked) selectedScanItems.add(item) else selectedScanItems.remove(item)
                                            }
                                        )
                                        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
                                            Text(item[0].substringAfter("nas/"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Text(item[1], color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { 
                                     if (selectedScanItems.isEmpty()) {
                                         Toast.makeText(context, "No items selected", Toast.LENGTH_SHORT).show()
                                         return@Button
                                     }
                                     isReDownloading = true
                                     scope.launch(Dispatchers.IO) {
                                         // Trigger re-download with filtered list
                                         reDownloadFiles(context, repository, selectedScanItems.toList()) { job ->
                                             scope.launch(Dispatchers.Main) {
                                                 if (job != null) {
                                                     if (backupQueue.any { it.label == job.label } || (isBackupRunning && currentBackupLabel == job.label)) {
                                                         Toast.makeText(context, "Job already in queue", Toast.LENGTH_SHORT).show()
                                                     } else {
                                                          
                                                          BackupManager.addToQueue(job)
                        val intent = Intent(context, BackupService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                                                         Toast.makeText(context, "Queued: ${job.label}", Toast.LENGTH_SHORT).show()
                                                     }
                                                 } else {
                                                     Toast.makeText(context, "Failed to create download job", Toast.LENGTH_SHORT).show()
                                                 }
                                                 isReDownloading = false
                                                 // Don't close dialog automatically, user might want to do more?
                                                 // Or maybe clear selection?
                                                 // Let's keep dialog open but clear selection?
                                                 // scanResults = null // Close dialog
                                             }
                                         }
                                     }
                                },
                                enabled = !isReDownloading
                            ) { 
                                if (isReDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Processing...")
                                } else {
                                    Text("Re-Download (${selectedScanItems.size})")
                                }
                            }
                        },

                        dismissButton = {
                            Row {
                                Button(
                                    onClick = {
                                        if (selectedScanItems.isEmpty()) {
                                            Toast.makeText(context, "No items selected", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        scope.launch(Dispatchers.IO) {
                                            val count = renameScannedFiles(selectedScanItems.toList()) { msg ->
                                                scope.launch(Dispatchers.Main) { logToUiAndFile(context, msg) }
                                            }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Renamed $count files", Toast.LENGTH_SHORT).show()
                                                // Update the list? We need to refresh scan results or remove renamed items.
                                                // For now, let's close the dialog to force re-scan or just keep it open (items will now point to non-existent files though).
                                                // Safest is to close or refresh. Let's close for now.
                                                scanResults = null
                                            }
                                        }
                                    },
                                    enabled = !isReDownloading,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Rename (${selectedScanItems.size})")
                                }
                                Button(
                                    onClick = {
                                        if (selectedScanItems.isEmpty()) {
                                            Toast.makeText(context, "No items selected", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        val paths = selectedScanItems.map { it[0] }
                                        val splitJob = BackupJob.SplitFiles(paths)
                                        BackupManager.addToQueue(splitJob)
                                        
                                        // Start service if not running
                                        val serviceIntent = Intent(context, BackupService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(serviceIntent)
                                        } else {
                                            context.startService(serviceIntent)
                                        }
                                        
                                        Toast.makeText(context, "Split task added to queue", Toast.LENGTH_SHORT).show()
                                        scanResults = null // Close dialog
                                    },
                                    enabled = !isReDownloading,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Text("Split (> 9.1GB)")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { scanResults = null }, enabled = !isReDownloading) { Text("Close") }
                            }
                        }
                    )
                }

                // Action Buttons Row (Backup, Download, Debug Log)
                if (!isSelectionMode) {
                     var showDebugLog by remember { mutableStateOf(false) }
                     
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            onClick = {
                                val newJob = BackupJob.RecursiveBackup(sourcePath)
                                if (isBackupRunning && currentBackupLabel == newJob.label) {
                                    Toast.makeText(context, "Task is currently running!", Toast.LENGTH_SHORT).show()
                                } else if (backupQueue.any { it.label == newJob.label }) {
                                     Toast.makeText(context, "Task is already in queue!", Toast.LENGTH_SHORT).show()
                                } else {
                                    BackupManager.addToQueue(newJob)
                                    val intent = Intent(context, BackupService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    localStatusMessage = "Queued: Full Backup of $sourcePath"
                                }
                            }
                        ) {
                            Text("Backup", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            onClick = { showDebugLog = !showDebugLog },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text(if (showDebugLog) "Hide Log" else "Show Log", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
    
                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            onClick = {
                                // Recursive Download (Download All including subdirs)
                                val newJob = BackupJob.RecursiveBackup(sourcePath, moveOnNas = false)
                                
                                if (isBackupRunning && currentBackupLabel == newJob.label) {
                                    Toast.makeText(context, "Task is currently running!", Toast.LENGTH_SHORT).show()
                                } else if (backupQueue.any { it.label == newJob.label }) {
                                     Toast.makeText(context, "Task is already in queue!", Toast.LENGTH_SHORT).show()
                                } else {
                                    BackupManager.addToQueue(newJob)
                                    val intent = Intent(context, BackupService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    localStatusMessage = "Queued Full Download: $sourcePath"
                                    logToUiAndFile(context, "Queued Recursive Download: $sourcePath")
                                }
                            }
                        ) {
                            Text("Download", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        
                        Button(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            onClick = { 
                                if (Build.VERSION.SDK_INT >= 30) {
                                    if (!Environment.isExternalStorageManager()) {
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                            intent.data = android.net.Uri.parse("package:${context.packageName}")
                                            context.startActivity(intent)
                                            Toast.makeText(context, "Please grant 'All files access'", Toast.LENGTH_LONG).show()
                                        } catch (e: Exception) {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        showScanDialog = true 
                                    }
                                } else {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        Toast.makeText(context, "Storage permission required", Toast.LENGTH_LONG).show()
                                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        intent.data = android.net.Uri.parse("package:${context.packageName}")
                                        context.startActivity(intent)
                                    } else {
                                        showScanDialog = true 
                                    }
                                }
                            }
                        ) {
                            Text("Scan", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Google Photos UI
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Google Photos", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            
                            if (googleAccount == null) {
                                Button(
                                    onClick = {
                                        val intent = GoogleSignInHelper.getSignInIntent(context)
                                        googleSignInLauncher.launch(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Text("Sign in with Google")
                                }
                            } else {
                                Text("Signed in as: ${googleAccount?.email}", style = MaterialTheme.typography.bodySmall)
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            GoogleSignInHelper.signOut(context)
                                            googleAccount = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Sign Out")
                                    }
                                    
                                    Button(
                                        onClick = {
                                            if (isGoogleUploading) {
                                                logToUiAndFile(context, "Stopping Google Photos Upload Service...")
                                                try {
                                                    val intent = Intent(context, GooglePhotosUploadService::class.java)
                                                    context.stopService(intent)
                                                    com.bjkim.nas2gp.service.GooglePhotosUploadManager.setUploading(false)
                                                    logToUiAndFile(context, "Service stop requested.")
                                                } catch (e: Exception) {
                                                    logToUiAndFile(context, "Error stopping service: ${e.message}")
                                                }
                                            } else {
                                                val hasPermission = if (Build.VERSION.SDK_INT >= 30) {
                                                    Environment.isExternalStorageManager()
                                                } else {
                                                    androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                }
                                                
                                                if (!hasPermission) {
                                                    logToUiAndFile(context, "Storage permission is required to scan files.")
                                                    if (Build.VERSION.SDK_INT >= 30) {
                                                        logToUiAndFile(context, "Redirecting to Android 11+ All Files Access settings...")
                                                        try {
                                                            val permIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                                            permIntent.data = android.net.Uri.parse("package:${context.packageName}")
                                                            context.startActivity(permIntent)
                                                        } catch (e: Exception) {
                                                            val permIntent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                            context.startActivity(permIntent)
                                                        }
                                                    } else {
                                                        logToUiAndFile(context, "Requesting classic storage permissions for Android 10...")
                                                        storagePermissionLauncher.launch(
                                                            arrayOf(
                                                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                            )
                                                        )
                                                    }
                                                } else {
                                                    logToUiAndFile(context, "Starting Google Photos Upload Service...")
                                                    try {
                                                        val intent = Intent(context, GooglePhotosUploadService::class.java)
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                            context.startForegroundService(intent)
                                                        } else {
                                                            context.startService(intent)
                                                        }
                                                        logToUiAndFile(context, "Service start requested successfully.")
                                                    } catch (e: Exception) {
                                                        logToUiAndFile(context, "Error starting service: ${e.message}")
                                                        e.printStackTrace()
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(2f)
                                    ) {
                                        Text(if (isGoogleUploading) "Stop Upload" else "Start Upload to Google Photos")
                                    }
                                }
                                
                                if (isGoogleUploading || googleUploadProgress > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(googleUploadStatus, style = MaterialTheme.typography.bodySmall)
                                    
                                    // Current File Progress
                                    if (googleCurrentFileTotalBytes > 0) {
                                        val currentMb = googleCurrentFileBytesUploaded / (1024f * 1024f)
                                        val totalMb = googleCurrentFileTotalBytes / (1024f * 1024f)
                                        Text(String.format(Locale.getDefault(), "File: %.1f MB / %.1f MB", currentMb, totalMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                    LinearProgressIndicator(
                                        progress = if (googleCurrentFileTotalBytes > 0) (googleCurrentFileBytesUploaded.toFloat() / googleCurrentFileTotalBytes) else (googleUploadProgress / 100f),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                                    )
                                    
                                    // Overall Job Progress
                                    if (googleTotalBytesTarget > 0) {
                                        val overallMb = googleTotalBytesUploaded / (1024f * 1024f)
                                        val targetMb = googleTotalBytesTarget / (1024f * 1024f)
                                        val etaText = if (googleEtaString.isNotEmpty()) " (ETA: $googleEtaString)" else ""
                                        Text(String.format(Locale.getDefault(), "Overall: %.1f MB / %.1f MB%s", overallMb, targetMb, etaText), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        
                                        LinearProgressIndicator(
                                            progress = googleTotalBytesUploaded.toFloat() / googleTotalBytesTarget,
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    if (showDebugLog) {
                    val scrollState = rememberScrollState()
                    var oldMax by remember { mutableStateOf(0) }
                    
                    // Initial scroll to bottom
                    LaunchedEffect(Unit) {
                        delay(100) // Wait for initial layout
                        scrollState.scrollTo(Int.MAX_VALUE)
                    }
                    
                    // Smart auto-scroll: Only scroll if user is already at the bottom
                    LaunchedEffect(scrollState.maxValue) {
                         val newMax = scrollState.maxValue
                         val currentScroll = scrollState.value
                         // Check if we were at bottom relative to OLD max
                         val wasAtBottom = (oldMax - currentScroll) < 100
                         
                         if (wasAtBottom || oldMax == 0) {
                              scrollState.scrollTo(newMax)
                         }
                         oldMax = newMax
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
            
            if (googleSignInError != null) {
                AlertDialog(
                    onDismissRequest = { googleSignInError = null },
                    title = { Text("Google 로그인 에러") },
                    text = { Text(googleSignInError!!) },
                    confirmButton = {
                        TextButton(onClick = { googleSignInError = null }) {
                            Text("확인")
                        }
                    }
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
                                    localStatusMessage = "Deleting leftovers..."
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
                                    localStatusMessage = "Deleted $delCount files and $deletedDirs empty folders."
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

// FileUtils functions moved to com.bjkim.nas2gp.utils package

suspend fun reDownloadFiles(
    context: Context,
    repository: SynologyRepository,
    scanResults: List<List<String>>,
    onJobCreated: (BackupJob?) -> Unit
) {
    val nasBaseDir = "/homes/bjkim/google_photo_backup"
    val filesToDownload = mutableListOf<FileInfo>()
    
    scanResults.forEach { item ->
        val localPath = item[0]
        val file = File(localPath)
        
        // 1. Delete local file - SKIPPED FOR ATOMIC REPLACE
        // if (file.exists()) {
        //     file.delete()
        // }
        
        // 2. Construct NAS Path and FileInfo
        val relativePath = localPath.substringAfter("/nas/")
        if (relativePath.isNotEmpty() && relativePath != localPath) {
             val nasPath = "$nasBaseDir/$relativePath"
             // Helper FileInfo (size/time not critical for generic download logic if we skip size check? 
             // Logic in BackupJob loop checks 'file.fileSize'. 
             // Wait, logic uses 'file.additional?.size'. If null, it assumes 0.
             // If local file deleted, size check (local vs remote) is skipped (targetFile.exists() is false).
             // So simplistic FileInfo is fine.
             filesToDownload.add(FileInfo(
                 path = nasPath,
                 name = file.name,
                 isdir = false,
                 additional = AdditionalInfo(size = 0, time = null)
             ))
        }
    }
    
    if (filesToDownload.isNotEmpty()) {
        val job = BackupJob.SelectedBackup(filesToDownload, nasBaseDir, moveOnNas = false)
        onJobCreated(job)
    } else {
        onJobCreated(null)
    }
}

suspend fun repairMetadata(
    context: Context,
    repository: SynologyRepository,
    scanResults: List<List<String>>,
    onLog: (String) -> Unit
): Int {
    val nasBaseDir = "/homes/bjkim/google_photo_backup"
    var repairedCount = 0
    
    val groups = scanResults.groupBy { 
        val path = it[0]
        val substring = path.substringAfter("/nas/", "")
        if (substring.isNotEmpty() && substring.contains("/")) {
             substring.substringBeforeLast("/")
        } else {
             ""
        }
    }
    
    groups.forEach { (relativeParent, items) ->
        val nasParentPath = if (relativeParent.isEmpty()) nasBaseDir else "$nasBaseDir/$relativeParent"
        onLog("Fetching metadata from: $nasParentPath")
        
        val remoteFiles = repository.listFiles(nasParentPath)
        
        if (remoteFiles.isEmpty()) {
            onLog("Failed to list files or empty: $nasParentPath")
            return@forEach
        }
        
        items.forEach { item ->
            val localPath = item[0]
            val fileName = File(localPath).name
            val remoteFile = remoteFiles.find { it.name == fileName }
            
            if (remoteFile != null) {
                val mtime = remoteFile.additional?.time?.mtime ?: 0L
                if (mtime > 0) {
                     if (repairFile(File(localPath), mtime * 1000L)) {
                         repairedCount++
                         onLog("Repaired: $fileName")
                     } else {
                         onLog("Failed to repair: $fileName")
                     }
                }
            } else {
                 onLog("File not found on NAS: $fileName")
            }
        }
    }
    return repairedCount
}

fun repairFile(file: File, time: Long): Boolean {
    try {
        if (!file.exists()) return false
        val successFs = file.setLastModified(time)
        var successExif = false
        
        if (file.extension.lowercase() in setOf("jpg", "jpeg", "heic", "webp", "png")) {
             try {
                 val exif = ExifInterface(file.absolutePath)
                 val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                 exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(Date(time)))
                 exif.saveAttributes()
                 successExif = true
             } catch (e: Exception) {
                 // ignore
             }
        }
        return successFs || successExif
    } catch(e: Exception) {
        return false
    }
}

suspend fun scanForIssues(onLog: (String) -> Unit, checkMetadata: Boolean, targetDate: String?, includeAllFiles: Boolean = false, checkLargeFiles: Boolean = false): List<List<String>> {
    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    val nasDir = File(downloadDir, "nas")
    val list = mutableListOf<List<String>>()
    var scannedFileCount = 0
    
    onLog("Scan Started: ${nasDir.absolutePath}")
    
    if (!nasDir.exists()) {
        onLog("Scan Skipped: Directory not found")
        return emptyList()
    }
    
    val rootFiles = nasDir.listFiles()
    if (rootFiles == null) {
        onLog("Scan Warning: listFiles() returned null. Check Permissions.")
    } else {
        onLog("Root contains ${rootFiles.size} items: ${rootFiles.take(10).joinToString { it.name }}")
    }
    
    val targetTimeRange = if (!targetDate.isNullOrEmpty()) {
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(targetDate)
            if (date != null) {
                val cal = Calendar.getInstance()
                cal.time = date
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis
                start to end
            } else null
        } catch (e: Exception) { null }
    } else null

    nasDir.walk()
        .onFail { f, e ->
            onLog("Walk Error: ${f.absolutePath} - ${e.message}")
        }
        .onEnter {
            onLog("Scanning Dir: ${it.absolutePath}")
            true
        }
        .filter { it.isFile && !it.name.equals("folder.jpg", ignoreCase = true) }
        .forEach { file ->
            scannedFileCount++
            val reasons = mutableListOf<String>()
            val ext = file.extension.lowercase()
            
            if (includeAllFiles) {
                reasons.add("Found")
            }
            
            if (checkMetadata && ext in setOf("jpg", "jpeg", "heic", "webp", "png")) {
                 try {
                    val exif = ExifInterface(file.absolutePath)
                    val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    if (date.isNullOrEmpty()) {
                        reasons.add("No EXIF Date")
                    }
                 } catch (e: Exception) {
                     // ignore
                 }
            }
            
            if (targetTimeRange != null) {
                if (file.lastModified() in targetTimeRange.first until targetTimeRange.second) {
                    reasons.add("Modified on $targetDate")
                }
            }
            
            if (checkLargeFiles) {
                val ninePointOneGb = (9.1 * 1024 * 1024 * 1024).toLong()
                if (file.length() >= ninePointOneGb) {
                    val sizeGb = file.length().toDouble() / (1024 * 1024 * 1024)
                    reasons.add(String.format(Locale.getDefault(), "Large File (%.2f GB)", sizeGb))
                }
            }
            
            if (reasons.isNotEmpty()) {
                list.add(listOf(file.absolutePath, reasons.joinToString(", ")))
            }
        }
    onLog("Scan Finished. Scanned $scannedFileCount files. Found ${list.size} issues.")
    return list
}

fun renameScannedFiles(scanResults: List<List<String>>, onLog: (String) -> Unit): Int {
    var renamedCount = 0
    // scanResults is list of [absolutePath, reason]
    scanResults.forEach { item ->
        try {
            val originalPath = item[0]
            val file = File(originalPath)
            if (file.exists()) {
                val parentDir = file.parentFile
                val name = file.name
                val extension = file.extension
                val nameWithoutExtension = file.nameWithoutExtension
                
                val newName = if (extension.isNotEmpty()) {
                    "${nameWithoutExtension}_rename.$extension"
                } else {
                    "${name}_rename"
                }
                
                val newFile = File(parentDir, newName)
                if (file.renameTo(newFile)) {
                    renamedCount++
                    onLog("Renamed: ${file.name} -> ${newFile.name}")
                } else {
                    onLog("Failed to rename: ${file.name}")
                }
            } else {
                onLog("File not found: $originalPath")
            }
        } catch (e: Exception) {
            onLog("Error renaming ${item[0]}: ${e.message}")
        }
    }
    return renamedCount
}

