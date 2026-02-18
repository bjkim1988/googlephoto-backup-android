package com.example.test.service

import com.example.test.model.BackupJob
import com.example.test.repository.SynologyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object BackupManager {
    private val _queue = MutableStateFlow<List<BackupJob>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _isBackupRunning = MutableStateFlow(false)
    val isBackupRunning = _isBackupRunning.asStateFlow()

    val statusMessage = MutableStateFlow("Ready")
    val debugLog = MutableStateFlow("")
    val progress = MutableStateFlow(0)
    val currentFile = MutableStateFlow("")
    val storageStats = MutableStateFlow<Pair<Long, Long>?>(null)
    
    // Config
    var username: String = ""
    var host: String = ""
    
    // Shared repository instance
    var repository: SynologyRepository? = null

    fun addToQueue(job: BackupJob) {
        val current = _queue.value.toMutableList()
        current.add(job)
        _queue.value = current
    }

    fun popQueue(): BackupJob? {
        val current = _queue.value.toMutableList()
        if (current.isNotEmpty()) {
            val job = current.removeAt(0)
            _queue.value = current
            return job
        }
        return null
    }
    
    fun clearQueue() {
        _queue.value = emptyList()
    }

    fun setRunning(running: Boolean) {
        _isBackupRunning.value = running
    }
    
    fun appendLog(msg: String) {
        debugLog.value += msg + "\n"
    }
}
