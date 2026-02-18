package com.example.test.utils

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

suspend fun saveToMediaStore(context: Context, inputStream: InputStream, fileName: String, subDir: String, expectedSize: Long, lastModified: Long? = null, onLog: (String) -> Unit = {}): Boolean {
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
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        }

            if (!canUseFileApi) {
                // Use MediaStore (Scopred Storage)
                onLog("Saving via MediaStore API...")
                val resolver = context.contentResolver
                var uri: android.net.Uri? = null
                var currentFileName = fileName
                var retryCount = 0
                
                while (uri == null && retryCount < 3) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, currentFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$subDir/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (lastModified != null && lastModified > 0) {
                            put(MediaStore.MediaColumns.DATE_MODIFIED, lastModified / 1000)
                            put(MediaStore.MediaColumns.DATE_ADDED, lastModified / 1000)
                            put(MediaStore.MediaColumns.DATE_TAKEN, lastModified)
                        }
                    }
                    
                    try {
                        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    } catch (e: Exception) {
                        onLog("Insert Exception: ${e.message}")
                    }
                    
                    if (uri == null) {
                        onLog("MediaStore insert failed for $currentFileName. Retrying with new name...")
                        val nameNoExt = fileName.substringBeforeLast('.')
                        val extPart = fileName.substringAfterLast('.', "")
                        val dot = if (extPart.isNotEmpty()) "." else ""
                        currentFileName = "${nameNoExt}_${System.currentTimeMillis()}$dot$extPart"
                        retryCount++
                    }
                }
                
                if (uri == null) {
                    onLog("Error: MediaStore insert failed permanently (uri is null). Check permissions.")
                    return@withContext false
                }
                
                val finalUri = uri!!
                finalUri.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    // Inject EXIF DateTimeOriginal if missing (images only)
                    if (lastModified != null && lastModified > 0 && isImage) {
                        try {
                            resolver.openFileDescriptor(it, "rw")?.use { pfd ->
                                val exif = ExifInterface(pfd.fileDescriptor)
                                val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                if (existing.isNullOrEmpty()) {
                                    onLog("Metadata: No EXIF Date in $currentFileName. Injecting NAS time...")
                                    val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(Date(lastModified)))
                                    exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(Date(lastModified)))
                                    exif.saveAttributes()
                                    onLog("Metadata: EXIF Injection Success.")
                                } else {
                                    onLog("Metadata: EXIF Date exists. Skipping injection.")
                                }
                            }
                        } catch (e: Exception) {
                            onLog("Metadata: EXIF Injection Failed: ${e.message}")
                            e.printStackTrace()
                        }
                    }

                    // Inject MP4 creation_time if missing (mp4/mov videos)
                    if (lastModified != null && lastModified > 0 && isMp4Video) {
                        try {
                            resolver.openFileDescriptor(it, "rw")?.use { pfd ->
                                val fd = pfd.fileDescriptor
                                val fis = java.io.FileInputStream(fd)
                                val channel = fis.channel
                                val tempFile = File(context.cacheDir, "temp_mp4_meta_${System.currentTimeMillis()}")
                                tempFile.outputStream().use { os -> fis.copyTo(os) }
                                channel.close()
                                fis.close()
                                
                                onLog("Metadata: Checking MP4 atoms for $currentFileName...")
                                val injected = injectMp4CreationDate(tempFile, lastModified)
                                if (injected) {
                                    onLog("Metadata: MP4 Creation Time injected.")
                                    // Write back only if changed
                                    resolver.openOutputStream(it, "wt")?.use { os ->
                                        tempFile.inputStream().use { input -> input.copyTo(os) }
                                    }
                                } else {
                                     onLog("Metadata: MP4 time already set or not found.")
                                }
                                tempFile.delete()
                            }
                        } catch (e: Exception) {
                             onLog("Metadata: MP4 Injection Failed: ${e.message}")
                            e.printStackTrace()
                        }
                    }

                    val updateValues = ContentValues()
                    updateValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(it, updateValues, null, null)
                    true
                } ?: false
            } else {
                onLog("Saving via File API...")
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subDir)
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                
                val finalFile = targetFile
                if (finalFile.exists()) {
                    onLog("File exists. Overwriting: $fileName")
                }
                
                FileOutputStream(finalFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }

                // Set file modified time
                if (lastModified != null && lastModified > 0) {
                    finalFile.setLastModified(lastModified)
                }

                // Inject EXIF DateTimeOriginal if missing (images only)
                if (lastModified != null && lastModified > 0 && isImage) {
                    try {
                        val exif = ExifInterface(finalFile.absolutePath)
                        val existing = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        if (existing.isNullOrEmpty()) {
                            onLog("Metadata: No EXIF Date. Injecting NAS time...")
                            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(Date(lastModified)))
                            exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(Date(lastModified)))
                            exif.saveAttributes()
                            onLog("Metadata: EXIF Injection Success.")
                        } else {
                            onLog("Metadata: EXIF Date exists.")
                        }
                    } catch (e: Exception) {
                        onLog("Metadata: EXIF Injection Failed: ${e.message}")
                        e.printStackTrace()
                    }
                }

                // Inject MP4 creation_time if missing (mp4/mov videos)
                if (lastModified != null && lastModified > 0 && isMp4Video) {
                    try {
                        onLog("Metadata: Checking MP4 atoms...")
                        val injected = injectMp4CreationDate(finalFile, lastModified)
                        if (injected) onLog("Metadata: MP4 Creation Time injected.")
                        else onLog("Metadata: MP4 time already set.")
                    } catch (e: Exception) {
                        onLog("Metadata: MP4 Injection Failed: ${e.message}")
                        e.printStackTrace()
                    }
                }

                android.media.MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), null, null)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Invoke callback with error message
            onLog("Save error: ${e.message}")
            false
        }
    }
}

/**
 * Inject creation_time into MP4/MOV file's mvhd atom.
 * MP4 epoch starts 1904-01-01 (offset = 2082844800 seconds from Unix epoch).
 * Only writes if the existing creation_time is 0.
 */
fun injectMp4CreationDate(file: File, lastModifiedMs: Long): Boolean {
    val MP4_EPOCH_OFFSET = 2082844800L
    val mp4Time = (lastModifiedMs / 1000) + MP4_EPOCH_OFFSET
    var injected = false

    val raf = RandomAccessFile(file, "rw")
    try {
        val fileSize = raf.length()
        var pos = 0L

        // Find moov atom at top level
        var moovPos = -1L
        var moovSize = 0L
        while (pos < fileSize) {
            raf.seek(pos)
            if (pos + 8 > fileSize) break
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (size < 8 && size != 1L) break // invalid
            val atomSize = if (size == 1L) {
                if (pos + 16 > fileSize) break
                raf.readLong() // 64-bit extended size
            } else {
                size
            }
            if (atomSize <= 0) break

            if (type == "moov") {
                moovPos = pos
                moovSize = atomSize
                break
            }
            pos += atomSize
        }

        if (moovPos < 0) {
            return false
        }

        // Find mvhd atom inside moov
        val headerSize = 8L // moov header size
        var innerPos = moovPos + headerSize
        val moovEnd = moovPos + moovSize

        while (innerPos < moovEnd) {
            raf.seek(innerPos)
            if (innerPos + 8 > moovEnd) break
            val size = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (size < 8 && size != 1L) break
            val atomSize = if (size == 1L) {
                if (innerPos + 16 > moovEnd) break
                raf.readLong()
            } else {
                size
            }
            if (atomSize <= 0) break

            if (type == "mvhd") {
                // mvhd found: [size(4)][type(4)][version(1)][flags(3)][creation_time][modification_time]...
                val dataStart = innerPos + 8
                raf.seek(dataStart)
                val version = raf.readByte().toInt() and 0xFF
                raf.skipBytes(3) // flags

                if (version == 0) {
                    // 4-byte timestamps
                    val creationTimePos = dataStart + 4
                    raf.seek(creationTimePos)
                    val existingCreation = raf.readInt().toLong() and 0xFFFFFFFFL
                    if (existingCreation == 0L) {
                        raf.seek(creationTimePos)
                        raf.writeInt(mp4Time.toInt()) // creation_time
                        raf.writeInt(mp4Time.toInt()) // modification_time
                        injected = true
                    }
                } else if (version == 1) {
                    // 8-byte timestamps
                    val creationTimePos = dataStart + 4
                    raf.seek(creationTimePos)
                    val existingCreation = raf.readLong()
                    if (existingCreation == 0L) {
                        raf.seek(creationTimePos)
                        raf.writeLong(mp4Time) // creation_time
                        raf.writeLong(mp4Time) // modification_time
                        injected = true
                    }
                }
                break
            }
            innerPos += atomSize
        }
    } finally {
        raf.close()
    }
    return injected
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
