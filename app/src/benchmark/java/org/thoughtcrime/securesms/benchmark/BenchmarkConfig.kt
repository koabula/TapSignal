package org.thoughtcrime.securesms.benchmark

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for a benchmark test session
 */
@Parcelize
data class BenchmarkConfig(
  val messageCount: Int,
  val messageType: MessageType,
  val messageSize: MessageSize,
  val intervalMs: Long = 1000,
  val deviceRole: DeviceRole = DeviceRole.SENDER,
  val autoStartV2Mode: Boolean = true,
  val sessionId: String = System.currentTimeMillis().toString()
) : Parcelable

/**
 * Predefined message sizes for different types
 */
enum class MessageSize {
  SMALL,
  MEDIUM,
  LARGE;
  
  fun getTextLength(): Int = when (this) {
    SMALL -> 50
    MEDIUM -> 200
    LARGE -> 1000
  }
  
  fun getVoiceDurationSeconds(): Int = when (this) {
    SMALL -> 10
    MEDIUM -> 30
    LARGE -> 60
  }
  
  fun getImageSizeKB(): Int = when (this) {
    SMALL -> 100
    MEDIUM -> 500
    LARGE -> 2048
  }
  
  fun getVideoSizeMB(): Int = when (this) {
    SMALL -> 5
    MEDIUM -> 20
    LARGE -> 50
  }
  
  fun getFileSizeKB(): Long = when (this) {
    SMALL -> 100
    MEDIUM -> 1024
    LARGE -> 10240
  }
  
  fun getLargeFileSizeMB(): Int = when (this) {
    SMALL -> 10
    MEDIUM -> 50
    LARGE -> 100
  }
}

