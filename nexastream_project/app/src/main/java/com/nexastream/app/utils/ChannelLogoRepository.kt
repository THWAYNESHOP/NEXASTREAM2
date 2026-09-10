package com.nexastream.app.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

object ChannelLogoRepository {
    private const val LOGO_PATHS_URL = "https://jaruba.github.io/channel-logos/logo_paths.json"
    private const val EXPORT_BASE_URL = "https://jaruba.github.io/channel-logos/export/"
    
    private var logoMap: Map<String, String>? = null
    private val isFetching = AtomicBoolean(false)
    private val gson = Gson()

    suspend fun getLogoUrl(channelName: String, variation: String = "transparent-color"): String? {
        if (logoMap == null) {
            fetchLogos()
        }
        
        val map = logoMap ?: return null
        val normalizedInput = channelName.lowercase().trim()
        
        // 1. Exact match
        map[normalizedInput]?.let { return "$EXPORT_BASE_URL$variation$it" }
        
        // 2. Remove "HD", "SD", "TV" and try again
        val strippedInput = normalizedInput
            .replace(Regex("\\b(hd|sd|tv|ultra hd|4k)\\b"), "")
            .trim()
            .replace(Regex("\\s+"), " ")
        
        map[strippedInput]?.let { return "$EXPORT_BASE_URL$variation$it" }
        
        // 3. Try to find a key that contains the input or is contained by the input
        // This is expensive, so only for a few hundred logos it's okay.
        // The repo has thousands, but let's see.
        
        // Optimization: iterate keys
        for ((key, path) in map) {
            if (key.length > 3 && (strippedInput.contains(key) || key.contains(strippedInput))) {
                return "$EXPORT_BASE_URL$variation$path"
            }
        }

        return null
    }

    private suspend fun fetchLogos() = withContext(Dispatchers.IO) {
        if (logoMap != null) return@withContext
        if (isFetching.getAndSet(true)) {
            // Wait for existing fetch if needed (optional, just return for now)
            return@withContext
        }
        
        try {
            Log.d("ChannelLogoRepo", "Fetching logo paths from $LOGO_PATHS_URL")
            val request = Request.Builder()
                .url(LOGO_PATHS_URL)
                .build()
            
            NetworkClient.minimal.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string()
                    if (json != null) {
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        logoMap = gson.fromJson(json, type)
                        Log.d("ChannelLogoRepo", "Successfully loaded ${logoMap?.size} channel logos")
                    }
                } else {
                    Log.e("ChannelLogoRepo", "Failed to fetch logos: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("ChannelLogoRepo", "Error fetching channel logos", e)
        } finally {
            isFetching.set(false)
        }
    }
}
