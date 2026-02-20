package com.bjkim.nas2gp.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "uploaded_files")
data class UploadedFile(
    @PrimaryKey val filePath: String,
    val uploadToken: String?,
    val mediaItemId: String?,
    val uploadedAt: Long
)

@Dao
interface UploadedFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(uploadedFile: UploadedFile)

    @Query("SELECT * FROM uploaded_files WHERE filePath = :filePath LIMIT 1")
    suspend fun getByPath(filePath: String): UploadedFile?

    @Query("SELECT EXISTS(SELECT 1 FROM uploaded_files WHERE filePath = :filePath LIMIT 1)")
    suspend fun isUploaded(filePath: String): Boolean
}

@Database(entities = [UploadedFile::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadedFileDao(): UploadedFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

