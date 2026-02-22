package com.bjkim.nas2gp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object GooglePhotosUploadManager {
    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    val statusMessage = MutableStateFlow("Ready to Upload")
    val progress = MutableStateFlow(0) // percentage 0-100
    
    val currentFileBytesUploaded = MutableStateFlow(0L)
    val currentFileTotalBytes = MutableStateFlow(0L)
    
    val totalBytesUploaded = MutableStateFlow(0L)
    val totalBytesTarget = MutableStateFlow(0L)
    
    val etaString = MutableStateFlow("")

    fun setUploading(uploading: Boolean) {
        _isUploading.value = uploading
    }
}

