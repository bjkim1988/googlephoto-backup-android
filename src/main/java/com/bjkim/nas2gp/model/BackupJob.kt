package com.bjkim.nas2gp.model

import com.bjkim.nas2gp.network.FileInfo

sealed class BackupJob {
    abstract val label: String
    abstract val sourcePath: String
    abstract val moveOnNas: Boolean
    
    data class RecursiveBackup(override val sourcePath: String, override val moveOnNas: Boolean = true) : BackupJob() {
        override val label = if (moveOnNas) "Backup: $sourcePath" else "Download: $sourcePath"
    }
    
    data class SelectedBackup(
        val files: List<FileInfo>, 
        override val sourcePath: String, 
        override val moveOnNas: Boolean = true
    ) : BackupJob() {
        override val label = if (moveOnNas) "Backup (${files.size} files)" else "Download (${files.size} files)"
    }

    data class SplitFiles(val filePaths: List<String>) : BackupJob() {
        override val label = "Split (${filePaths.size} files)"
        override val sourcePath = "" 
        override val moveOnNas = false
    }
}

