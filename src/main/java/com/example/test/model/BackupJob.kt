package com.example.test.model

import com.example.test.network.FileInfo

sealed class BackupJob {
    abstract val label: String
    abstract val sourcePath: String
    abstract val moveOnNas: Boolean
    
    data class RecursiveBackup(override val sourcePath: String, override val moveOnNas: Boolean = true) : BackupJob() {
        override val label = "Full Backup: $sourcePath"
    }
    
    data class SelectedBackup(
        val files: List<FileInfo>, 
        override val sourcePath: String, 
        override val moveOnNas: Boolean = true
    ) : BackupJob() {
        override val label = "Selected: ${files.size} files" + if (!moveOnNas) " (Download Only)" else ""
    }
}
