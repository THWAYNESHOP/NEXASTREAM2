package com.nexastream.app.cast

import android.content.Context
import android.util.Log
import androidx.media3.cast.CastPlayer
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val castContext: CastContext? by lazy {
        try {
            CastContext.getSharedInstance(context)
        } catch (e: Exception) {
            Log.e("CastManager", "Failed to get CastContext", e)
            null
        }
    }

    private var _castPlayer: CastPlayer? = null
    val castPlayer: CastPlayer?
        get() {
            if (_castPlayer == null) {
                castContext?.let {
                    _castPlayer = CastPlayer(it, CastMediaItemConverter())
                }
            }
            return _castPlayer
        }

    fun isCasting(): Boolean {
        return castContext?.sessionManager?.currentCastSession?.isConnected == true
    }

    fun release() {
        _castPlayer?.release()
        _castPlayer = null
    }
}
