package com.nexastream.app.backup

import android.content.Context
import android.util.Log
import androidx.room.Transaction
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.database.dao.EpisodeDao
import com.nexastream.app.database.dao.MovieDao
import com.nexastream.app.database.dao.TvShowDao
import com.nexastream.app.database.dao.SeasonDao
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.WatchItem
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserDataCache
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ProviderBackupContext(
    val name: String,
    val movieDao: MovieDao,
    val tvShowDao: TvShowDao,
    val episodeDao: EpisodeDao,
    val seasonDao: SeasonDao,
    val provider: Provider
)

class BackupRestoreManager(
    private val context: Context,
    private val providers: List<ProviderBackupContext>
) {
    private val TAG = "BackupVerify"

    suspend fun refreshCachesFromDatabase(): Boolean {
        return try {
            providers.forEach { buildCacheForProvider(it) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error refreshing caches from database", t)
            false
        }
    }

    fun exportDatabaseZip(): ByteArray? {
        return try {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                providers.forEach { providerCtx ->
                    addDatabaseFilesToZip(zip, providerCtx.name)
                }
            }
            output.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting database zip", t)
            null
        }
    }

    suspend fun importDatabaseZip(zipBytes: ByteArray): Boolean {
        return try {
            AppDatabase.resetInstance()
            restoreDatabaseZip(ByteArrayInputStream(zipBytes))
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error importing database zip", t)
            false
        }
    }

    fun exportUserData(): String? {
        return try {
            val root = JSONObject()
            root.put("version", 4)
            root.put("exportedAt", System.currentTimeMillis())

            val providersArray = JSONArray()
            for (p in providers) {
                val moviesToExport = p.movieDao.getAll()
                    .filter { it.isWatched || it.watchedDate != null || it.watchHistory != null || it.isFavorite }
                val tvShowsToExport = p.tvShowDao.getAllForBackup()
                    .filter { it.isWatching || it.isFavorite }
                val episodesToExport = p.episodeDao.getAllForBackup()
                    .filter { it.isWatched || it.watchedDate != null || it.watchHistory != null }
                
                if (moviesToExport.isEmpty() && tvShowsToExport.isEmpty() && episodesToExport.isEmpty()) {
                    continue
                }

                val providerObj = JSONObject()
                providerObj.put("name", p.name)

                val moviesArray = JSONArray()
                moviesToExport.forEach { movie ->
                    val obj = JSONObject().apply {
                        put("id", movie.id)
                        put("title", movie.title)
                        put("poster", movie.poster)
                        put("banner", movie.banner)
                        put("isFavorite", movie.isFavorite)
                        put("favoritedAtMillis", movie.favoritedAtMillis ?: JSONObject.NULL)
                        put("isWatched", movie.isWatched)
                        put("watchedDate", movie.watchedDate?.timeInMillis ?: JSONObject.NULL)
                        put("watchHistory", movie.watchHistory?.toJson() ?: JSONObject.NULL)
                    }
                    moviesArray.put(obj)
                }
                providerObj.put("movies", moviesArray)

                val tvShowsArray = JSONArray()
                tvShowsToExport.forEach { show ->
                    val obj = JSONObject().apply {
                        put("id", show.id)
                        put("title", show.title)
                        put("poster", show.poster)
                        put("banner", show.banner)
                        put("isFavorite", show.isFavorite)
                        put("favoritedAtMillis", show.favoritedAtMillis ?: JSONObject.NULL)
                        put("isWatching", show.isWatching)
                    }
                    tvShowsArray.put(obj)
                }
                providerObj.put("tvShows", tvShowsArray)

                val seasonsArray = JSONArray()
                p.seasonDao.getAllForBackup()
                    .forEach { season ->
                        val obj = JSONObject().apply {
                            put("id", season.id)
                            put("number", season.number)
                            put("title", season.title)
                            put("poster", season.poster)
                            put("tvShowId", season.tvShow?.id)
                        }
                        seasonsArray.put(obj)
                    }
                providerObj.put("seasons", seasonsArray)

                val episodesArray = JSONArray()
                episodesToExport.forEach { ep ->
                    val obj = JSONObject().apply {
                        put("id", ep.id)
                        put("number", ep.number)
                        put("title", ep.title)
                        put("poster", ep.poster)
                        put("tvShowId", ep.tvShow?.id)
                        put("seasonId", ep.season?.id)
                        put("isWatched", ep.isWatched)
                        put("watchedDate", ep.watchedDate?.timeInMillis ?: JSONObject.NULL)
                        put("watchHistory", ep.watchHistory?.toJson() ?: JSONObject.NULL)
                    }
                    episodesArray.put(obj)
                }
                providerObj.put("episodes", episodesArray)

                providersArray.put(providerObj)
            }

            root.put("providers", providersArray)
            root.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during exportUserData", t)
            null
        }
    }


    @Transaction
    suspend fun importUserData(json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            val providersArray = obj.optJSONArray("providers") ?: return false
            val backupVersion = obj.optInt("version", 1)

            for (i in 0 until providersArray.length()) {
                val providerObj = providersArray.optJSONObject(i) ?: continue
                val providerName = providerObj.optString("name") ?: continue
                val providerCtx = providers.find { it.name == providerName } ?: continue

                val seasonsArr = providerObj.optJSONArray("seasons")
                if (seasonsArr != null) {
                    val seasonsToSave = mutableListOf<Season>()
                    for (j in 0 until seasonsArr.length()) {
                        val s = seasonsArr.optJSONObject(j) ?: continue
                        val season = Season(
                            id = s.optString("id", ""),
                            number = s.optInt("number", 0)
                        ).apply {
                            title = s.optStringOrNull("title")
                            poster = s.optStringOrNull("poster")
                            s.optStringOrNull("tvShowId")?.let { tvId -> tvShow = TvShow(tvId, "") }
                        }
                        seasonsToSave.add(season)
                    }
                    if (seasonsToSave.isNotEmpty()) {
                        providerCtx.seasonDao.saveAll(seasonsToSave)
                    }
                }

                val tvShowsArr = providerObj.optJSONArray("tvShows")
                if (tvShowsArr != null) {
                    for (j in 0 until tvShowsArr.length()) {
                        val s = tvShowsArr.optJSONObject(j) ?: continue
                        val isFavorite = s.optBoolean("isFavorite", false)
                        val favoritedAtMillis = s.optLongOrNull("favoritedAtMillis")
                        val isWatching = s.optBoolean("isWatching", false)

                        val tvShow = TvShow(
                            id = s.optString("id", ""),
                            title = s.optString("title", "")
                        ).apply {
                            poster = s.optStringOrNull("poster")
                            banner = s.optStringOrNull("banner")
                            this.isFavorite = isFavorite
                            this.favoritedAtMillis = favoritedAtMillis
                            this.isWatching = isWatching
                        }
                        providerCtx.tvShowDao.save(tvShow)
                    }
                }

                val moviesArr = providerObj.optJSONArray("movies")
                if (moviesArr != null) {
                    for (j in 0 until moviesArr.length()) {
                        val m = moviesArr.optJSONObject(j) ?: continue
                        val isFavorite = m.optBoolean("isFavorite", false)
                        val favoritedAtMillis = m.optLongOrNull("favoritedAtMillis")
                        val isWatched = m.optBoolean("isWatched", false)
                        val watchedDate = m.optLongOrNull("watchedDate")?.toCalendar()
                        val watchHistory = m.optJSONObject("watchHistory")?.toWatchHistory()
                        
                        val movie = Movie(
                            id = m.optString("id", ""),
                            title = m.optString("title", "")
                        ).apply {
                            poster = m.optStringOrNull("poster")
                            banner = m.optStringOrNull("banner")
                            this.isFavorite = isFavorite
                            this.favoritedAtMillis = favoritedAtMillis
                            this.isWatched = isWatched
                            this.watchedDate = watchedDate
                            this.watchHistory = watchHistory
                        }
                        providerCtx.movieDao.save(movie)
                    }
                }

                val episodesArr = providerObj.optJSONArray("episodes")
                if (episodesArr != null) {
                    for (j in 0 until episodesArr.length()) {
                        val e = episodesArr.optJSONObject(j) ?: continue
                        val isWatched = e.optBoolean("isWatched", false)
                        val watchedDate = e.optLongOrNull("watchedDate")?.toCalendar()
                        val watchHistory = e.optJSONObject("watchHistory")?.toWatchHistory()

                        val ep = Episode(id = e.optString("id", "")).apply {
                            number = e.optInt("number", 0)
                            title = e.optStringOrNull("title")
                            poster = e.optStringOrNull("poster")
                            e.optStringOrNull("tvShowId")?.let { tvId -> tvShow = TvShow(tvId, "") }
                            e.optStringOrNull("seasonId")?.let { sId -> season = Season(sId, 0) }
                            this.isWatched = isWatched
                            this.watchedDate = watchedDate
                            this.watchHistory = watchHistory
                        }
                        providerCtx.episodeDao.save(ep)
                    }
                }

                buildCacheForProvider(providerCtx)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Error during importUserData", t)
            false
        }
    }

    private suspend fun buildCacheForProvider(providerCtx: ProviderBackupContext) {
        try {
            val movies = providerCtx.movieDao.getFavorites().first()
            val tvShows = providerCtx.tvShowDao.getFavorites().first()
            val watchingMovies = providerCtx.movieDao.getWatchingMovies().first()
            val watchingEpisodes = providerCtx.episodeDao.getWatchingEpisodes().first()

            UserDataCache.writeMovies(context, providerCtx.provider, movies + watchingMovies)
            UserDataCache.writeTvShows(context, providerCtx.provider, tvShows)
            UserDataCache.writeEpisodes(context, providerCtx.provider, watchingEpisodes)
        } catch (e: Exception) {
            Log.e(TAG, "Error building cache for provider ${providerCtx.name}", e)
        }
    }

    private fun addDatabaseFilesToZip(zip: ZipOutputStream, providerName: String) {
        val dbName = sanitizedDbName(providerName)
        listOf("", "-wal", "-shm").forEach { suffix ->
            val file = context.getDatabasePath("$dbName.db$suffix")
            if (!file.exists()) return@forEach
            zip.putNextEntry(ZipEntry("databases/${file.name}"))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun restoreDatabaseZip(input: InputStream) {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("databases/")) {
                    val fileName = entry.name.removePrefix("databases/")
                    val target = context.getDatabasePath(fileName)
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    target.outputStream().use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun sanitizedDbName(providerName: String): String {
        return providerName.lowercase(Locale.getDefault())
            .replace("[^a-z0-9]".toRegex(), "_")
            .replace("__+".toRegex(), "_")
            .trim('_')
    }


}
private fun Long.toCalendar(): Calendar = Calendar.getInstance().apply { timeInMillis = this@toCalendar }

private fun WatchItem.WatchHistory.toJson(): JSONObject =
    JSONObject().apply {
        put("lastEngagementTimeUtcMillis", lastEngagementTimeUtcMillis)
        put("lastPlaybackPositionMillis", lastPlaybackPositionMillis)
        put("durationMillis", durationMillis)
    }

private fun JSONObject.toWatchHistory(): WatchItem.WatchHistory? {
    val duration = optLong("durationMillis", 0L)
    if (duration <= 0) return null
    return WatchItem.WatchHistory(
        lastEngagementTimeUtcMillis = optLong("lastEngagementTimeUtcMillis", 0L),
        lastPlaybackPositionMillis = optLong("lastPlaybackPositionMillis", 0L),
        durationMillis = duration
    )
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}

private fun JSONObject.optStringOrNull(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name) else null
}
