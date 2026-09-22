package com.nexastream.app.providers

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.utils.UserPreferences
import java.util.Calendar

class TvContentProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.nexastream.app.search"
        private const val SEARCH_SUGGEST = 1

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST)
            addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", SEARCH_SUGGEST)
        }

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
        )
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (URI_MATCHER.match(uri) != SEARCH_SUGGEST) return null

        val query = selectionArgs?.get(0)?.lowercase() ?: return null
        val matrixCursor = MatrixCursor(COLUMNS)

        val context = context ?: return null

        try {
            val database = AppDatabase.getInstance(context)
            
            val movies = database.movieDao().searchMovies(query, 10, 0)
            movies.forEach { movie ->
                matrixCursor.addRow(arrayOf(
                    movie.id.hashCode().toLong(),
                    movie.title,
                    movie.overview,
                    movie.poster,
                    "video/mp4",
                    movie.released?.get(Calendar.YEAR) ?: 0,
                    (movie.runtime ?: 0) * 60 * 1000,
                    movie.id,
                    "movie"
                ))
            }

            val tvShows = database.tvShowDao().searchTvShows(query, 10, 0)
            tvShows.forEach { tvShow ->
                matrixCursor.addRow(arrayOf(
                    tvShow.id.hashCode().toLong(),
                    tvShow.title,
                    tvShow.overview,
                    tvShow.poster,
                    "video/mp4",
                    tvShow.released?.get(Calendar.YEAR) ?: 0,
                    (tvShow.runtime ?: 0) * 60 * 1000,
                    tvShow.id,
                    "tv_show"
                ))
            }
        } catch (e: Exception) {
            // Log error or handle cases where DB is not ready
        }

        return matrixCursor
    }

    override fun getType(uri: Uri): String? {
        return SearchManager.SUGGEST_MIME_TYPE
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
