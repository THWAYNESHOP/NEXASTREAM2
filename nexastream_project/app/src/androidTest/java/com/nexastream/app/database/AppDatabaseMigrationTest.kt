package com.nexastream.app.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val openedHelpers = mutableListOf<SupportSQLiteOpenHelper>()

    @After
    fun tearDown() {
        openedHelpers.forEach { it.close() }
        openedHelpers.clear()
    }

    @Test
    fun migration13To14AddsDownloadProgressColumns() {
        val db = createDownloadsDatabase(
            dbName = "migration_13_14_${System.nanoTime()}.db",
            extraColumns = ""
        )

        AppDatabase.MIGRATION_13_14.migrate(db)

        val columns = downloadColumns(db)
        assertTrue(columns.contains("downloadedSize"))
        assertTrue(columns.contains("downloadSpeed"))
        assertTrue(columns.contains("etaSeconds"))
        assertEquals("0", downloadColumnDefault(db, "downloadedSize"))
        assertEquals("0", downloadColumnDefault(db, "downloadSpeed"))

        db.query("SELECT downloadedSize, downloadSpeed, etaSeconds FROM downloads WHERE id = 'movie-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
        }
    }

    @Test
    fun migration13To14HandlesPartiallyMigratedDownloadsTable() {
        val db = createDownloadsDatabase(
            dbName = "migration_13_14_partial_${System.nanoTime()}.db",
            extraColumns = ", `downloadedSize` INTEGER NOT NULL DEFAULT 0"
        )

        AppDatabase.MIGRATION_13_14.migrate(db)

        val columns = downloadColumns(db)
        assertTrue(columns.contains("downloadedSize"))
        assertTrue(columns.contains("downloadSpeed"))
        assertTrue(columns.contains("etaSeconds"))
    }

    @Test
    fun migration15To16CreatesLiveTvTables() {
        val dbName = "migration_15_16_${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers.add(helper)
        val db = helper.writableDatabase

        AppDatabase.MIGRATION_15_16.migrate(db)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("epg_programs" in tables)
        assertTrue("live_stream_health" in tables)
        assertTrue("program_reminders" in tables)
        assertTrue("live_recordings" in tables)
    }

    @Test
    fun migration16To17CreatesChannelToolsTables() {
        val dbName = "migration_16_17_${System.nanoTime()}.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers.add(helper)
        val db = helper.writableDatabase

        AppDatabase.MIGRATION_16_17.migrate(db)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("live_channel_preferences" in tables)
        assertTrue("live_playback_diagnostics" in tables)
        assertTrue("xmltv_channels" in tables)
        assertTrue("epg_channel_mappings" in tables)
    }

    private fun createDownloadsDatabase(
        dbName: String,
        extraColumns: String
    ): SupportSQLiteDatabase {
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `downloads` (
                                `id` TEXT NOT NULL,
                                `title` TEXT NOT NULL,
                                `poster` TEXT,
                                `filePath` TEXT NOT NULL,
                                `url` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `progress` INTEGER NOT NULL,
                                `totalSize` INTEGER NOT NULL,
                                `quality` TEXT,
                                `headers` TEXT,
                                `mimeType` TEXT,
                                `errorMessage` TEXT,
                                `createdAt` INTEGER NOT NULL
                                $extraColumns,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO downloads (
                                id, title, poster, filePath, url, status, progress, totalSize,
                                quality, headers, mimeType, errorMessage, createdAt
                            ) VALUES (
                                'movie-1', 'Movie 1', NULL, '/tmp/movie-1.mp4', 'https://example.test/movie.m3u8',
                                'QUEUED', 0, 0, NULL, NULL, NULL, NULL, 1
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openedHelpers.add(helper)
        return helper.writableDatabase
    }

    private fun downloadColumns(db: SupportSQLiteDatabase): Set<String> {
        return db.query("PRAGMA table_info(`downloads`)").use { cursor ->
            val columns = mutableSetOf<String>()
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            columns
        }
    }

    private fun downloadColumnDefault(db: SupportSQLiteDatabase, column: String): String? {
        return db.query("PRAGMA table_info(`downloads`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    return@use if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
                }
            }
            null
        }
    }
}
