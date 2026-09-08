package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.db.dao.ChatDao
import com.example.data.db.dao.CommandLogDao
import com.example.data.db.dao.CustomConnectionDao
import com.example.data.db.dao.FileLogDao
import com.example.data.db.dao.GrantedFolderDao
import com.example.data.db.entities.ConversationEntity
import com.example.data.db.entities.MessageEntity
import com.example.data.db.entities.CommandLogEntity
import com.example.data.db.entities.CustomConnectionEntity
import com.example.data.db.entities.FileLogEntity
import com.example.data.db.entities.GrantedFolderEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        CommandLogEntity::class,
        FileLogEntity::class,
        CustomConnectionEntity::class,
        GrantedFolderEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun commandLogDao(): CommandLogDao
    abstract fun fileLogDao(): FileLogDao
    abstract fun customConnectionDao(): CustomConnectionDao
    abstract fun grantedFolderDao(): GrantedFolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "claude_shell_agent.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
