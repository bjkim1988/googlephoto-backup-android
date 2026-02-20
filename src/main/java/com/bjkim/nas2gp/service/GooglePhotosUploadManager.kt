package com.bjkim.nas2gp.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object GooglePhotosUploadManager {
    private val _isUploading = MutableStateFlow(false)
    val isUploading = _isUploading.asStateFlow()

    val statusMessage = MutableStateFlow("Ready to Upload")
    val progress = MutableStateFlow(0)
    
    fun setUploading(uploading: Boolean) {
        _isUploading.value = uploading
    }
}

