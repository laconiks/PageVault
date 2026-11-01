package com.local.pocketarchive.data.local

import androidx.room.TypeConverter

enum class CaptureStatus {
    PENDING,
    CAPTURING_DOM,
    SUMMARIZING_AI,
    COMPLETED,
    FAILED
}

class CaptureStatusConverter {
    @TypeConverter
    fun fromStatus(status: CaptureStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): CaptureStatus =
        runCatching { CaptureStatus.valueOf(value) }.getOrDefault(CaptureStatus.FAILED)
}
