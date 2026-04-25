package dev.brahmkshatriya.echo.history.db

import android.app.Application
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun create(app: Application) = Room.databaseBuilder(
            app, HistoryDatabase::class.java, "history-db"
        ).fallbackToDestructiveMigration(true).build()
    }
}
