package dev.iruki.classtime.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun toExceptionType(value: String): ExceptionType = ExceptionType.valueOf(value)

    @TypeConverter
    fun fromExceptionType(type: ExceptionType): String = type.name
}
