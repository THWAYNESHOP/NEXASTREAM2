package com.nexastream.app.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexastream.app.models.Season
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.format
import com.nexastream.app.utils.toCalendar
import java.util.Calendar

class Converters {

    @TypeConverter
    fun fromCalendar(value: Calendar?): String? {
        return value?.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }

    @TypeConverter
    fun toCalendar(value: String?): Calendar? {
        return value?.toCalendar()
    }


    @TypeConverter
    fun fromTvShow(value: TvShow?): String? {
        return value?.id
    }

    @TypeConverter
    fun toTvShow(value: String?): TvShow? {
        return value?.let { TvShow(it, "") }
    }


    @TypeConverter
    fun fromSeason(value: Season?): String? {
        return value?.id
    }

    @TypeConverter
    fun toSeason(value: String?): Season? {
        return value?.let { Season(it, 0) }
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { Gson().toJson(it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        return value?.let {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(it, type)
        }
    }
}
