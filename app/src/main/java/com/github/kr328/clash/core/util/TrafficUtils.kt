package com.github.kr328.clash.core.util

import com.github.kr328.clash.core.model.Traffic
import java.util.Locale

fun Traffic.trafficUpload(): String {
    return formatPackedTraffic(this ushr 32, isSpeed = false)
}

fun Traffic.trafficDownload(): String {
    return formatPackedTraffic(this and 0xFFFFFFFFL, isSpeed = false)
}

fun Traffic.trafficSpeedUpload(): String {
    return formatPackedTraffic(this ushr 32, isSpeed = true)
}

fun Traffic.trafficSpeedDownload(): String {
    return formatPackedTraffic(this and 0xFFFFFFFFL, isSpeed = true)
}

fun Traffic.trafficTotal(): String {
    val upload = rawUpload()
    val download = rawDownload()
    return trafficString(upload + download)
}

fun Traffic.rawUpload(): Long = scaleTraffic(this ushr 32)
fun Traffic.rawDownload(): Long = scaleTraffic(this and 0xFFFFFFFFL)

fun formatPackedTraffic(value: Long, isSpeed: Boolean = false): String {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF
    val suffix = if (isSpeed) "/s" else ""

    return when (type) {
        0L -> {
            if (isSpeed && data == 0L) "0 KB/s"
            else "$data B$suffix"
        }
        1L -> {
            // In libbridge.so, data = (bytes * 100) / 1024
            val kb = data.toDouble() / 100.0
            if (kb >= 1024.0) {
                String.format(Locale.US, "%.1f MB%s", kb / 1024.0, suffix)
            } else {
                String.format(Locale.US, "%.1f KB%s", kb, suffix)
            }
        }
        2L -> {
            // In libbridge.so, data = (bytes * 100) / (1024 * 1024)
            val mb = data.toDouble() / 100.0
            if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB%s", mb / 1024.0, suffix)
            } else {
                String.format(Locale.US, "%.1f MB%s", mb, suffix)
            }
        }
        3L -> {
            // In libbridge.so, data = (bytes * 100) / (1024 * 1024 * 1024)
            val gb = data.toDouble() / 100.0
            String.format(Locale.US, "%.2f GB%s", gb, suffix)
        }
        else -> if (isSpeed) "0 KB/s" else "0 B"
    }
}

fun trafficString(bytes: Long): String {
    val b = if (bytes < 0) 0L else bytes
    return when {
        b >= 1024L * 1024 * 1024 -> {
            val data = b.toDouble() / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "%.2f GB", data)
        }
        b >= 1024L * 1024 -> {
            val data = b.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB", data)
        }
        b >= 1024L -> {
            val data = b.toDouble() / 1024.0
            String.format(Locale.US, "%.1f KB", data)
        }
        else -> {
            "$b B"
        }
    }
}

fun formatSpeedString(bytesPerSec: Long): String {
    val b = if (bytesPerSec < 0) 0L else bytesPerSec
    return when {
        b >= 1024L * 1024 * 1024 -> {
            val data = b.toDouble() / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "%.2f GB/s", data)
        }
        b >= 1024L * 1024 -> {
            val data = b.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB/s", data)
        }
        b >= 1024L -> {
            val data = b.toDouble() / 1024.0
            String.format(Locale.US, "%.1f KB/s", data)
        }
        b > 0L -> {
            "$b B/s"
        }
        else -> {
            "0 KB/s"
        }
    }
}

private fun scaleTraffic(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF

    // In libbridge.so, data is scaled up by 100 (e.g. data = (bytes * 100) / 1024)
    return when (type) {
        0L -> data
        1L -> (data * 1024L) / 100L
        2L -> (data * 1024L * 1024L) / 100L
        3L -> (data * 1024L * 1024L * 1024L) / 100L
        else -> data
    }
}
