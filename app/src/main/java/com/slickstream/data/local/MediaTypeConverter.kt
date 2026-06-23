package com.slickstream.data.local

import androidx.room.TypeConverter
import com.slickstream.core.model.MediaType

/** Persists [MediaType] as its enum name; tolerant of unknown/legacy values on read. */
class MediaTypeConverter {

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String?): MediaType = MediaType.fromName(value)
}
