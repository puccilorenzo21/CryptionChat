package com.lollo1344.cryptionchat

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.FileInputStream
import java.security.SecureRandom

@Database(entities = [LocalMessageEntity::class, VaultEntryEntity::class, TrustedIdentityEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun vaultDao(): VaultDao
    abstract fun trustedIdentityDao(): TrustedIdentityDao

    companion object {
        private const val TAG = "AppDatabase"
        private const val DB_NAME = "cryptionchat_local_db"
        private const val PREF_DB_PASSPHRASE = "local_db_passphrase"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dr_vault` (`ciphertextHash` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `plaintext` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`ciphertextHash`))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trusted_identities` (`peerUserId` INTEGER NOT NULL, `ikSignPubB64` TEXT NOT NULL, `ikDhPubB64` TEXT NOT NULL, `fingerprintHex` TEXT NOT NULL, `trustState` TEXT NOT NULL, `firstSeenAt` INTEGER NOT NULL, `verifiedAt` INTEGER, PRIMARY KEY(`peerUserId`))"
                )
            }
        }

        init {
            try {
                System.loadLibrary("sqlcipher")
            } catch (e: Throwable) {
                Log.e(TAG, "Impossibile caricare la libreria sqlcipher", e)
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private fun generateAndPersistPassphrase(context: Context): ByteArray {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            val passHex = randomBytes.joinToString("") { "%02x".format(it) }
            SecurePrefs.get(context).edit().putString(PREF_DB_PASSPHRASE, passHex).commit()
            return hexStringToByteArray(passHex)
        }

        private fun getOrCreateDatabasePassphrase(context: Context): Pair<ByteArray, Boolean> {
            val prefs = SecurePrefs.get(context)
            val passHex = prefs.getString(PREF_DB_PASSPHRASE, null)
            if (!SessionLifecycleHelper.isValidHexPassphrase(passHex)) {
                return generateAndPersistPassphrase(context) to true
            }
            return hexStringToByteArray(passHex!!) to false
        }

        private fun deleteDatabaseFiles(context: Context, dbName: String) {
            try {
                context.deleteDatabase(dbName)
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante context.deleteDatabase($dbName)", e)
            }
            val dbFile = context.getDatabasePath(dbName)
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val target = if (suffix.isEmpty()) dbFile else java.io.File("${dbFile.path}$suffix")
                try {
                    if (target.exists()) target.delete()
                } catch (_: Exception) {
                }
            }
        }

        /**
         * Chiude l'istanza corrente ed elimina fisicamente il database SQLCipher e i suoi file WAL/SHM.
         */
        fun deleteLocalDatabase(context: Context) {
            synchronized(this) {
                try {
                    INSTANCE?.close()
                } catch (_: Throwable) {
                } finally {
                    INSTANCE = null
                }
                deleteDatabaseFiles(context.applicationContext, DB_NAME)
            }
        }

        private fun hasPlaintextSqliteHeader(dbFile: java.io.File): Boolean {
            if (!dbFile.exists()) return false
            return try {
                val header = ByteArray(16)
                FileInputStream(dbFile).use { stream ->
                    val bytesRead = stream.read(header)
                    bytesRead >= 15 && String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante il controllo dell'header del database", e)
                false
            }
        }

        private fun buildDatabase(appContext: Context, passphrase: ByteArray): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return@synchronized it }

                val appContext = context.applicationContext
                val dbFile = appContext.getDatabasePath(DB_NAME)
                val hadExistingDb = dbFile.exists()
                val plaintextHeader = hasPlaintextSqliteHeader(dbFile)
                val (passphrase, newlyGenerated) = getOrCreateDatabasePassphrase(appContext)

                // Se il vecchio DB era in chiaro OPPURE la passphrase era persa/appena rigenerata,
                // mantenere il vecchio file cifrato garantirebbe SQLiteException (code 26).
                if (SessionLifecycleHelper.shouldResetBeforeOpen(hadExistingDb, plaintextHeader, newlyGenerated)) {
                    Log.w(TAG, "Reset preventivo del database locale (plaintext=$plaintextHeader, newKey=$newlyGenerated)")
                    deleteDatabaseFiles(appContext, DB_NAME)
                }

                val candidate = buildDatabase(appContext, passphrase)
                val verified = try {
                    candidate.openHelper.writableDatabase
                    candidate
                } catch (e: Exception) {
                    // Self-healing: se la chiave salvata non decifra il file su disco (es. reset parziale precedente),
                    // elimina il DB illeggibile, ruota la passphrase e ricrea un DB pulito invece di crashare.
                    Log.e(TAG, "Apertura database cifrato fallita (${e.message}). Ripristino database locale...", e)
                    try {
                        candidate.close()
                    } catch (_: Throwable) {
                    }
                    deleteDatabaseFiles(appContext, DB_NAME)
                    val freshPassphrase = generateAndPersistPassphrase(appContext)
                    val rebuilt = buildDatabase(appContext, freshPassphrase)
                    rebuilt.openHelper.writableDatabase
                    rebuilt
                }

                INSTANCE = verified
                verified
            }
        }
    }
}
