package com.bjkim.nas2gp.utils

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

private const val TAG = "FileUtils"

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

suspend fun saveToMediaStore(context: Context, inputStream: InputStream, fileName: String, subDir: String, expectedSize: Long, lastModified: Long? = null, atomicReplace: Boolean = false, onLog: (String) -> Unit = {}): Boolean {
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val isImage = ext in setOf("jpg", "jpeg", "png", "webp", "heic")
    val isMp4Video = ext in setOf("mp4", "mov")
    val mimeType = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    return withContext(Dispatchers.IO) {
        try {
            val canUseFileApi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true // Legacy storage allows File API on Android 10 and below
            }

            if (!canUseFileApi) {
                // MediaStore API (Scoped Storage without All Files Access)
                onLog("Saving via MediaStore API (Atomic Replace Not Supported fully)...")
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$subDir/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (lastModified != null && lastModified > 0) {
                        put(MediaStore.MediaColumns.DATE_MODIFIED, lastModified / 1000)
                        put(MediaStore.MediaColumns.DATE_ADDED, lastModified / 1000)
                        put(MediaStore.MediaColumns.DATE_TAKEN, lastModified)
                    }
                }
                
                var uri: android.net.Uri? = null
                try {
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } catch (e: Exception) {
                    onLog("Insert Exception: ${e.message}")
                }

                if (uri == null) {
                    onLog("Insert failed. Attempting to delete existing...")
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                    val selectionArgs = arrayOf(fileName)
                    
                    var deletedCount = 0
                    context.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                        selection, 
                        selectionArgs, 
                        null
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                        val pathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        
                        while (cursor.moveToNext()) {
                            if (idIndex >= 0) {
                                val id = cursor.getLong(idIndex)
                                val path = if (pathIndex >= 0) cursor.getString(pathIndex) else ""
                                if (path != null && (path.contains(subDir) || subDir.isEmpty())) {
                                    try {
                                        val deleteUri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                                        resolver.delete(deleteUri, null, null)
                                        deletedCount++
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                    if (deletedCount > 0) {
                        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    }
                }
                
                if (uri == null) return@withContext false
                
                resolver.openOutputStream(uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                
                val updateValues = ContentValues()
                updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, updateValues, null, null)
                true
                
            } else {
                onLog("Saving via File API...")
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
                if (!targetDir.exists()) targetDir.mkdirs()
                
                val targetFile = File(targetDir, fileName)
                
                if (atomicReplace && targetFile.exists()) {
                    onLog("Atomic Replace: Starting...")
                    val tmpFile = File(targetDir, "${fileName}_tmp")
                    val nameWithoutExt = fileName.substringBeforeLast('.')
                    val extOrEmpty = if (fileName.contains('.')) ".${fileName.substringAfterLast('.')}" else ""
                    val backupName = "${nameWithoutExt}_bk$extOrEmpty"
                    val backupFile = File(targetDir, backupName)
                    
                    if (tmpFile.exists()) tmpFile.delete()
                    if (backupFile.exists()) backupFile.delete()
                    
                    FileOutputStream(tmpFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    
                    if (targetFile.renameTo(backupFile)) {
                        if (tmpFile.renameTo(targetFile)) {
                            onLog("Atomic Replace: Success. Deleting backup...")
                            backupFile.delete()
                            
                            if (lastModified != null && lastModified > 0) {
                                targetFile.setLastModified(lastModified)
                            }
                            android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                            true
                        } else {
                            onLog("Atomic Replace Failed: Could not rename tmp to target. Restoring backup...")
                            backupFile.renameTo(targetFile)
                            false
                        }
                    } else {
                        onLog("Atomic Replace Failed: Could not backup original.")
                        tmpFile.delete()
                        false
                    }
                } else {
                    if (targetFile.exists()) {
                        onLog("File exists. Overwriting: $fileName")
                    }
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    if (lastModified != null && lastModified > 0) {
                        targetFile.setLastModified(lastModified)
                    }
                    
                    if (lastModified != null && lastModified > 0 && isImage) {
                        try {
                            val exif = ExifInterface(targetFile.absolutePath)
                            val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            if (existing.isNullOrEmpty()) {
                                val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(Date(lastModified)))
                                exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(Date(lastModified)))
                                exif.saveAttributes()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
                    true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onLog("Save error: ${e.message}")
            false
        }
    }
}

/**
 * Recursively deletes empty directories starting from the given root.
 * Returns the number of directories deleted.
 */
fun deleteEmptyDirectories(dir: File): Int {
    if (!dir.exists() || !dir.isDirectory) return 0
    var deletedCount = 0
    dir.listFiles()?.forEach { 
        if (it.isDirectory) {
            deletedCount += deleteEmptyDirectories(it)
        }
    }
    // Re-check after potential child directory deletion
    val contents = dir.listFiles()
    if (contents != null && contents.isEmpty()) {
        if (dir.delete()) {
            deletedCount++
        }
    }
    return deletedCount
}

fun appendLog(context: Context, msg: String) {
    try {
        val file = File(context.filesDir, "last_session_log.txt")
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

/**
 * Splits large video files using FFmpeg for perfect header reconstruction and seekability.
 */
suspend fun splitLargeFiles(
    context: Context, 
    filePaths: List<String>, 
    onLog: (String) -> Unit, 
    onProgress: (Int) -> Unit = {}
): Int {
    var totalSplitCount = 0
    val targetSizeLimit = 500L * 1024 * 1024 // 500MB
    val splitThreshold = 510L * 1024 * 1024 // Buffer
    
    filePaths.forEachIndexed { fileIndex, path ->
        try {
            val file = File(path)
            if (!file.exists() || file.length() < splitThreshold) return@forEachIndexed
            
            val totalLength = file.length()
            val totalDurationMs = getDurationMs(path)
            if (totalDurationMs <= 0) {
                onLog(" - Error: Could not determine duration for ${file.name}. Skipping.")
                return@forEachIndexed
            }

            val parentDir = file.parentFile
            val nameWithoutExt = file.nameWithoutExtension
            val ext = file.extension
            
            // Calculate part duration based on size ratio
            val partDurationMs = (totalDurationMs * targetSizeLimit) / totalLength
            val numParts = Math.ceil(totalLength.toDouble() / targetSizeLimit).toInt()
            
            onLog("FFmpeg Splitting: ${file.name} into approx $numParts parts...")

            for (partIdx in 0 until numParts) {
                coroutineContext.ensureActive()
                
                val startMs = partIdx * partDurationMs
                if (startMs >= totalDurationMs) break
                
                val partName = "${nameWithoutExt}_div_$partIdx.$ext"
                val partFile = File(parentDir, partName)
                
                val startTimeSec = startMs / 1000.0
                val durationSec = if (partIdx == numParts -1) 0.0 else (partDurationMs / 1000.0)
                
                onLog(" - Creating Part $partIdx (Starts at ${String.format("%.1f", startTimeSec)}s)...")
                onLog(" - Env Check: InReadable=${file.canRead()}, ParentWritable=${parentDir.canWrite()}")
                
                val args = if (durationSec > 0) {
                    arrayOf(
                        "-y", 
                        "-i", path, 
                        "-ss", String.format(Locale.US, "%.3f", startTimeSec), 
                        "-t", String.format(Locale.US, "%.3f", durationSec), 
                        "-c:v", "copy", 
                        "-c:a", "aac", 
                        "-b:a", "128k", 
                        "-map", "0:v", "-map", "0:a?", 
                        "-ignore_unknown",
                        "-avoid_negative_ts", "make_zero",
                        "-tag:v", "hvc1", // Explicitly set HEVC tag for compatibility
                        "-movflags", "+faststart", // Web/Mobile friendly
                        partFile.absolutePath
                    )
                } else {
                    arrayOf(
                        "-y", 
                        "-i", path, 
                        "-ss", String.format(Locale.US, "%.3f", startTimeSec), 
                        "-c:v", "copy", 
                        "-c:a", "aac", 
                        "-b:a", "128k",
                        "-map", "0:v", "-map", "0:a?", 
                        "-ignore_unknown", 
                        "-avoid_negative_ts", "make_zero",
                        "-tag:v", "hvc1",
                        "-movflags", "+faststart",
                        partFile.absolutePath
                    )
                }

                onLog(" - Executing FFmpeg: ${args.joinToString(" ")}")
                
                val session = FFmpegKit.executeWithArguments(args)
                if (ReturnCode.isSuccess(session.returnCode)) {
                    totalSplitCount++
                    onLog("   [OK] Part $partIdx created: ${partFile.name}")
                    
                    // Copy original timestamp
                    partFile.setLastModified(file.lastModified())
                    
                    // Verification + Thumbnail
                    verifyAndExtractThumbnail(context, partFile, onLog)
                    
                    // Media Scan
                    MediaScannerConnection.scanFile(context, arrayOf(partFile.absolutePath), null, null)
                } else {
                    val logs = session.allLogsAsString
                    onLog("   [ERR] FFmpeg failed for Part $partIdx (Code: ${session.returnCode})")
                    onLog("   [ERR] Input: $path")
                    onLog("   [ERR] Output: ${partFile.absolutePath}")
                    if (logs.isNotEmpty()) {
                        onLog("   Logs: ${logs.takeLast(1000)}") 
                    }
                    if (session.failStackTrace != null) {
                        onLog("   Stack: ${session.failStackTrace}")
                    }
                }
                
                val progress = ((fileIndex.toDouble() + (partIdx.toDouble() / numParts)) / filePaths.size * 100).toInt()
                onProgress(progress)
                
                if (partIdx >= 1) { 
                    onLog(" - Test Limit Reached (2 parts). Skipping rest.")
                    break 
                }
            }
        } catch (e: Exception) {
            onLog("Error splitting $path: ${e.message}")
        }
    }
    return totalSplitCount
}

private fun getDurationMs(path: String): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        retriever.release()
        dur
    } catch (e: Exception) {
        0L
    }
}

private fun verifyAndExtractThumbnail(context: Context, partFile: File, onLog: (String) -> Unit) {
    try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(partFile.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        
        if (duration != null) {
            val durMs = duration.toLong()
            onLog("   [VERIFY] Duration: ${durMs / 1000}s")
            
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) 
                ?: retriever.getFrameAtTime(1000000)
            
            if (bitmap != null) {
                onLog("   [OK] Thumbnail success.")
                val currentList = com.bjkim.nas2gp.service.BackupManager.splitThumbnails.value.toMutableList()
                currentList.add(bitmap)
                com.bjkim.nas2gp.service.BackupManager.splitThumbnails.value = currentList
            } else {
                onLog("   [WRN] Could not extract thumbnail (but header is valid).")
            }
        } else {
            onLog("   [ERR] File header invalid after split.")
        }
        retriever.release()
    } catch (e: Exception) {
        onLog("   [ERR] Verification error: ${e.message}")
    }
}
