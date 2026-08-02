package com.wirefinder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.database.sqlite.SQLiteException
import com.wirefinder.util.DbEncryption
import java.io.File

@Database(entities = [APNetwork::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apNetworkDao(): APNetworkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private var libraryLoaded = false

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ap_networks ADD COLUMN lastMatchType INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    deleteLegacyBackups(context)
                    buildEncrypted(context).also {
                        it.openHelper.writableDatabase
                    }
                } catch (e: SQLiteException) {
                    // Legacy plaintext or corrupted file: delete it and start fresh.
                    // Network data is re-imported from the CSV on next load.
                    // Keeping a plaintext copy around would expose every password.
                    deleteLegacyDatabase(context)
                    buildEncrypted(context).also {
                        it.openHelper.writableDatabase
                    }
                }.also { INSTANCE = it }
            }
        }

        private fun buildEncrypted(context: Context): AppDatabase {
            if (!libraryLoaded) {
                System.loadLibrary("sqlcipher")
                libraryLoaded = true
            }
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "wirefinder_db"
            )
                .addMigrations(MIGRATION_1_2)
                .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(DbEncryption.passphrase()))
                .build()
        }

        private fun deleteLegacyBackups(context: Context) {
            val dir = context.applicationContext.getDatabasePath("wirefinder_db").parentFile ?: return
            for (suffix in listOf("", "-wal", "-shm")) {
                File(dir, "wirefinder_db_legacy.bak$suffix").delete()
            }
        }

        private fun deleteLegacyDatabase(context: Context) {
            val dir = context.applicationContext.getDatabasePath("wirefinder_db").parentFile ?: return
            for (suffix in listOf("", "-wal", "-shm")) {
                File(dir, "wirefinder_db$suffix").delete()
                // Also remove copies archived by earlier builds
                File(dir, "wirefinder_db_legacy.bak$suffix").delete()
            }
        }
    }
}

class Converters {
    // Add any type converters if needed
}
