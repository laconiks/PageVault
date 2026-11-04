package com.local.webvaultr.data.local

import androidx.room.TypeConverter

class CaptureStatusConverter {
    @TypeConverter
    fun fromStatus(status: CaptureStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): CaptureStatus =
        runCatching { CaptureStatus.valueOf(value) }.getOrDefault(CaptureStatus.FAILED)

    @TypeConverter
    fun fromStringList(list: List<String>?): String =
        list?.joinToString(",") ?: ""

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList() else value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
