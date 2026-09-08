package com.example.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.db.entities.ConversationEntity
import com.example.data.db.entities.MessageEntity
import com.example.data.db.entities.CommandLogEntity
import com.example.data.db.entities.CustomConnectionEntity
import com.example.data.db.entities.FileLogEntity
import com.example.data.db.entities.GrantedFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesForConversation(convId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteMessagesForConversation(convId: String)
}

@Dao
interface CommandLogDao {
    @Query("SELECT * FROM command_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<CommandLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: CommandLogEntity)

    @Query("DELETE FROM command_logs")
    suspend fun clearLogs()
}

@Dao
interface FileLogDao {
    @Query("SELECT * FROM file_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentFileLogs(): Flow<List<FileLogEntity>>

    @Query("SELECT * FROM file_logs WHERE id = :id LIMIT 1")
    suspend fun getFileLogById(id: Long): FileLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileLog(log: FileLogEntity): Long

    @Query("DELETE FROM file_logs WHERE id = :id")
    suspend fun deleteFileLog(id: Long)

    @Query("DELETE FROM file_logs")
    suspend fun clearFileLogs()
}

@Dao
interface CustomConnectionDao {
    @Query("SELECT * FROM custom_connections ORDER BY createdAt ASC")
    fun getAllConnections(): Flow<List<CustomConnectionEntity>>

    @Query("SELECT * FROM custom_connections WHERE isActive = 1 LIMIT 1")
    fun getActiveConnection(): Flow<CustomConnectionEntity?>

    @Query("SELECT * FROM custom_connections WHERE id = :id LIMIT 1")
    suspend fun getConnectionById(id: String): CustomConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnection(conn: CustomConnectionEntity)

    @Query("DELETE FROM custom_connections WHERE id = :id")
    suspend fun deleteConnection(id: String)

    @Query("UPDATE custom_connections SET isActive = 0")
    suspend fun clearActiveStatus()

    @Query("UPDATE custom_connections SET isActive = 1 WHERE id = :id")
    suspend fun setActiveStatus(id: String)

    @Transaction
    suspend fun setActiveConnection(id: String) {
        clearActiveStatus()
        setActiveStatus(id)
    }
}

@Dao
interface GrantedFolderDao {
    @Query("SELECT * FROM granted_folders ORDER BY grantedAt DESC")
    fun getAllGrantedFolders(): Flow<List<GrantedFolderEntity>>

    @Query("SELECT * FROM granted_folders")
    suspend fun getAllGrantedFoldersSnapshot(): List<GrantedFolderEntity>

    @Query("SELECT * FROM granted_folders WHERE folderPath = :path LIMIT 1")
    suspend fun getByPath(path: String): GrantedFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGrantedFolder(folder: GrantedFolderEntity)

    @Query("DELETE FROM granted_folders WHERE id = :id")
    suspend fun deleteGrantedFolder(id: String)
}

