package org.thoughtcrime.securesms.benchmark

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core benchmark recording system for TAP latency measurement.
 * Records timestamps at critical points in message lifecycle.
 */
object BenchmarkRecorder {
  private const val TAG = "BenchmarkRecorder"
  
  private val enabled = AtomicBoolean(false)
  private val records = ConcurrentHashMap<String, MessageBenchmarkRecord>()
  private var currentSessionConfig: BenchmarkConfig? = null
  
  fun enable(config: BenchmarkConfig) {
    enabled.set(true)
    currentSessionConfig = config
    records.clear()
    Log.i(TAG, "Benchmark recording enabled: $config")
  }
  
  fun disable() {
    enabled.set(false)
    Log.i(TAG, "Benchmark recording disabled")
  }
  
  fun isEnabled(): Boolean = enabled.get()
  
  fun recordTimestamp(messageId: String, stage: BenchmarkStage) {
    if (!enabled.get()) return
    
    val timestamp = System.currentTimeMillis()
    val record = records.getOrPut(messageId) { 
      MessageBenchmarkRecord(messageId)
    }
    
    record.recordStage(stage, timestamp)
    
    Log.d(TAG, "Recorded [$messageId] $stage at $timestamp")
  }
  
  fun getRecord(messageId: String): MessageBenchmarkRecord? {
    return records[messageId]
  }
  
  fun getAllRecords(): List<MessageBenchmarkRecord> {
    return records.values.toList()
  }
  
  fun getCompletedRecords(): List<MessageBenchmarkRecord> {
    return records.values.filter { it.isComplete() }
  }
  
  fun generateReport(): BenchmarkReport {
    return BenchmarkReport(
      sessionId = System.currentTimeMillis().toString(),
      deviceRole = currentSessionConfig?.deviceRole ?: DeviceRole.UNKNOWN,
      config = currentSessionConfig,
      records = records.values.toList(),
      generatedAt = System.currentTimeMillis()
    )
  }
  
  fun clear() {
    records.clear()
    Log.i(TAG, "Benchmark records cleared")
  }
}

/**
 * Records all timestamps for a single message
 */
data class MessageBenchmarkRecord(
  val messageId: String,
  var messageType: MessageType = MessageType.UNKNOWN,
  var messageSizeBytes: Long = 0,
  
  // Sender-side timestamps
  var t0_sendClicked: Long? = null,
  var t1_encryptStart: Long? = null,
  var t2_encryptEnd: Long? = null,
  var t3_uploadStart: Long? = null,
  var t4_uploadEnd: Long? = null,
  
  // Receiver-side timestamps
  var t5_pollDetected: Long? = null,
  var t6_downloadStart: Long? = null,
  var t7_downloadEnd: Long? = null,
  var t8_decryptStart: Long? = null,
  var t9_decryptEnd: Long? = null,
  var t10_uiDisplayed: Long? = null,
  
  // Additional metadata
  var threadId: Long? = null,
  var recipientId: String? = null,
  var attachmentCount: Int = 0,
  var errorMessage: String? = null
) {
  
  fun recordStage(stage: BenchmarkStage, timestamp: Long) {
    when (stage) {
      BenchmarkStage.SEND_CLICKED -> t0_sendClicked = timestamp
      BenchmarkStage.ENCRYPT_START -> t1_encryptStart = timestamp
      BenchmarkStage.ENCRYPT_END -> t2_encryptEnd = timestamp
      BenchmarkStage.UPLOAD_START -> t3_uploadStart = timestamp
      BenchmarkStage.UPLOAD_END -> t4_uploadEnd = timestamp
      BenchmarkStage.POLL_DETECTED -> t5_pollDetected = timestamp
      BenchmarkStage.DOWNLOAD_START -> t6_downloadStart = timestamp
      BenchmarkStage.DOWNLOAD_END -> t7_downloadEnd = timestamp
      BenchmarkStage.DECRYPT_START -> t8_decryptStart = timestamp
      BenchmarkStage.DECRYPT_END -> t9_decryptEnd = timestamp
      BenchmarkStage.UI_DISPLAYED -> t10_uiDisplayed = timestamp
    }
  }
  
  fun isComplete(): Boolean {
    return t0_sendClicked != null && t10_uiDisplayed != null
  }
  
  fun getEndToEndLatency(): Long? {
    return if (t0_sendClicked != null && t10_uiDisplayed != null) {
      t10_uiDisplayed!! - t0_sendClicked!!
    } else null
  }
  
  fun getEncryptionTime(): Long? {
    return if (t1_encryptStart != null && t2_encryptEnd != null) {
      t2_encryptEnd!! - t1_encryptStart!!
    } else null
  }
  
  fun getUploadTime(): Long? {
    return if (t3_uploadStart != null && t4_uploadEnd != null) {
      t4_uploadEnd!! - t3_uploadStart!!
    } else null
  }
  
  fun getPollingWaitTime(): Long? {
    return if (t4_uploadEnd != null && t5_pollDetected != null) {
      t5_pollDetected!! - t4_uploadEnd!!
    } else null
  }
  
  fun getDownloadTime(): Long? {
    return if (t6_downloadStart != null && t7_downloadEnd != null) {
      t7_downloadEnd!! - t6_downloadStart!!
    } else null
  }
  
  fun getDecryptionTime(): Long? {
    return if (t8_decryptStart != null && t9_decryptEnd != null) {
      t9_decryptEnd!! - t8_decryptStart!!
    } else null
  }
  
  fun getUiRenderTime(): Long? {
    return if (t9_decryptEnd != null && t10_uiDisplayed != null) {
      t10_uiDisplayed!! - t9_decryptEnd!!
    } else null
  }
}

/**
 * Stages in the message lifecycle where timestamps are recorded
 */
enum class BenchmarkStage {
  SEND_CLICKED,      // T0: User clicks send button
  ENCRYPT_START,     // T1: Start encryption
  ENCRYPT_END,       // T2: Encryption complete
  UPLOAD_START,      // T3: Start upload to COS
  UPLOAD_END,        // T4: Upload complete
  POLL_DETECTED,     // T5: Receiver's poller detects new message
  DOWNLOAD_START,    // T6: Start download from COS
  DOWNLOAD_END,      // T7: Download complete
  DECRYPT_START,     // T8: Start decryption
  DECRYPT_END,       // T9: Decryption complete
  UI_DISPLAYED       // T10: Message displayed in UI
}

enum class MessageType {
  TEXT,
  VOICE,
  IMAGE,
  VIDEO,
  FILE,
  LARGE_FILE,
  UNKNOWN
}

enum class DeviceRole {
  SENDER,
  RECEIVER,
  BOTH,
  UNKNOWN
}

