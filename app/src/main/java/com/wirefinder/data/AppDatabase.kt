package com.wirefinder.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.wirefinder.util.DbEncryption
import java.io.File

@Database(entities = [APNetwork::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun apNetworkDao(): APNetworkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Volatile
        private var libraryLoaded = false

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ap_networks ADD COLUMN lastMatchType INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncrypted(context).also { INSTANCE = it }
            }
        }

        private fun buildEncrypted(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "wirefinder_db"
            )
                .addMigrations(MIGRATION_1_2)
                .openHelperFactory(LazyOpenHelperFactory())
                .build()
        }

        private fun loadLibrary() {
            if (!libraryLoaded) {
                synchronized(this) {
                    if (!libraryLoaded) {
                        System.loadLibrary("sqlcipher")
                        libraryLoaded = true
                    }
                }
            }
        }

        private fun deleteLegacyBackups(context: Context) {
            val dir = context.applicationContext.getDatabasePath("wirefinder_db").parentFile ?: return
            for (suffix in listOf("", "-wal", "-shm")) {
                File(dir, "wirefinder_db_legacy.bak$suffix").delete()
            }
        }

        private fun deleteDatabaseFiles(context: Context, name: String) {
            val dir = context.applicationContext.getDatabasePath(name).parentFile ?: return
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(dir, "$name$suffix").delete()
                File(dir, "${name}_legacy.bak$suffix").delete()
            }
        }

        private class LazyOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
            override fun create(
                configuration: SupportSQLiteOpenHelper.Configuration
            ): SupportSQLiteOpenHelper {
                return LazyOpenHelper(configuration)
            }
        }

        private class LazyOpenHelper(
            private val configuration: SupportSQLiteOpenHelper.Configuration
        ) : SupportSQLiteOpenHelper {

            private var delegate: SupportSQLiteOpenHelper? = null
            private var writeAheadLoggingEnabled: Boolean? = null

            @Synchronized
            private fun delegate(): SupportSQLiteOpenHelper {
                delegate?.let { return it }
                val created = createDelegate()
                writeAheadLoggingEnabled?.let { created.setWriteAheadLoggingEnabled(it) }
                delegate = created
                return created
            }

            private fun createDelegate(): SupportSQLiteOpenHelper {
                deleteLegacyBackups(configuration.context)
                loadLibrary()
                return net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                    DbEncryption.passphrase()
                ).create(configuration)
            }

            override val databaseName: String?
                get() = configuration.name

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                val current = delegate
                if (current != null) {
                    current.setWriteAheadLoggingEnabled(enabled)
                } else {
                    writeAheadLoggingEnabled = enabled
                }
            }

            override val writableDatabase: SupportSQLiteDatabase
                get() = try {
                    delegate().writableDatabase
                } catch (e: SQLiteException) {
                    reset()
                    delegate().writableDatabase
                }

            override val readableDatabase: SupportSQLiteDatabase
                get() = try {
                    delegate().readableDatabase
                } catch (e: SQLiteException) {
                    reset()
                    delegate().readableDatabase
                }

            @Synchronized
            private fun reset() {
                try {
                    delegate?.close()
                } catch (_: Exception) {
                }
                delegate = null
                deleteDatabaseFiles(configuration.context, configuration.name ?: "wirefinder_db")
            }

            override fun close() {
                delegate?.close()
                delegate = null
            }
        }
    }
}
